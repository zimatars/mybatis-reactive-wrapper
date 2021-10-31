package org.springframework.jdbc.datasource;

import org.apache.ibatis.toolkit.MybatisScheduler;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.lang.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager;
import org.springframework.transaction.reactive.GenericReactiveTransaction;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

public class MybatisReactiveTransactionManager extends AbstractReactiveTransactionManager implements InitializingBean {

    @Nullable
    private DataSource dataSource;

    private boolean enforceReadOnly = false;


    /**
     * Create a new @link DataSourceTransactionManager} instance.
     * A DataSource has to be set to be able to use it.
     * @see #setDataSource
     */
    public MybatisReactiveTransactionManager() {}

    /**
     * Create a new {@link MybatisReactiveTransactionManager} instance.
     * @param dataSource the JDBC DataSource to manage transactions for
     */
    public MybatisReactiveTransactionManager(DataSource dataSource) {
        this();
        setDataSource(dataSource);
        afterPropertiesSet();
    }


    /**
     * Set the JDBC {@link DataSource} that this instance should manage transactions for.
     * <p>This will typically be a locally defined {@link DataSource}, for example an connection pool.
     * <p><b>The {@link DataSource} passed in here needs to return independent {@link Connection}s.</b>
     * The {@link Connection}s may come from a pool (the typical case), but the {@link DataSource}
     * must not return scoped {@link Connection}s or the like.
     */
    public void setDataSource(@Nullable DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Return the JDBC {@link DataSource} that this instance manages transactions for.
     */
    @Nullable
    public DataSource getDataSource() {
        return this.dataSource;
    }

    /**
     * Obtain the {@link DataSource} for actual use.
     * @return the {@link DataSource} (never {@code null})
     * @throws IllegalStateException in case of no DataSource set
     */
    protected DataSource obtainDataSource() {
        DataSource dataSource = getDataSource();
        Assert.state(dataSource != null, "No DataSource set");
        return dataSource;
    }

    /**
     * Specify whether to enforce the read-only nature of a transaction (as indicated by
     * {@link TransactionDefinition#isReadOnly()} through an explicit statement on the
     * transactional connection: "SET TRANSACTION READ ONLY" as understood by Oracle,
     * MySQL and Postgres.
     * <p>The exact treatment, including any SQL statement executed on the connection,
     * can be customized through through {@link #prepareTransactionalConnection}.
     * @see #prepareTransactionalConnection
     */
    public void setEnforceReadOnly(boolean enforceReadOnly) {
        this.enforceReadOnly = enforceReadOnly;
    }

    /**
     * Return whether to enforce the read-only nature of a transaction through an
     * explicit statement on the transactional connection.
     * @see #setEnforceReadOnly
     */
    public boolean isEnforceReadOnly() {
        return this.enforceReadOnly;
    }

    @Override
    public void afterPropertiesSet() {
        if (getDataSource() == null) {
            throw new IllegalArgumentException("Property 'dataSource' is required");
        }
    }

    @Override
    protected Object doGetTransaction(TransactionSynchronizationManager synchronizationManager) throws TransactionException {
        DataSourceTransactionObject txObject = new DataSourceTransactionObject();
        ConnectionHolder conHolder = (ConnectionHolder) synchronizationManager.getResource(obtainDataSource());
        txObject.setConnectionHolder(conHolder, false);
        return txObject;
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
        return (txObject.hasConnectionHolder() && txObject.getConnectionHolder().isTransactionActive());
    }

    @Override
    protected Mono<Void> doBegin(TransactionSynchronizationManager synchronizationManager, Object transaction,
                                 TransactionDefinition definition) throws TransactionException {

        DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;

        return Mono.defer(() -> {
            Mono<Connection> connectionMono;

            if (!txObject.hasConnectionHolder() || txObject.getConnectionHolder().isSynchronizedWithTransaction()) {
                Mono<Connection> newCon = Mono.defer(()->{
                    try {
                       return Mono.just(obtainDataSource().getConnection());
                    } catch (SQLException e) {
                        e.printStackTrace();
                        return Mono.error(e);
                    }
                }).subscribeOn(MybatisScheduler.getScheduler());
                connectionMono = newCon.doOnNext(connection -> {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Acquired Connection [" + newCon + "] for JDBC transaction");
                    }
                    txObject.setConnectionHolder(new ConnectionHolder(connection), true);
                });
            }
            else {
                txObject.getConnectionHolder().setSynchronizedWithTransaction(true);
                connectionMono = Mono.just(txObject.getConnectionHolder().getConnection());
            }

            return connectionMono.flatMap(con -> {
                return prepareTransactionalConnection(con, definition, transaction).then(Mono.defer(()->{
                            try {
                                con.setAutoCommit(false);
                                return Mono.empty();
                            } catch (SQLException e) {
                                e.printStackTrace();
                                return Mono.error(e);
                            }
                        }))
                        .subscribeOn(MybatisScheduler.getScheduler())
                        .doOnSuccess(v -> {
                            txObject.getConnectionHolder().setTransactionActive(true);
                            Duration timeout = determineTimeout(definition);
                            if (!timeout.isNegative() && !timeout.isZero()) {
                                txObject.getConnectionHolder().setTimeoutInMillis(timeout.toMillis());
                            }
                            // Bind the connection holder to the thread.
                            if (txObject.isNewConnectionHolder()) {
                                synchronizationManager.bindResource(obtainDataSource(), txObject.getConnectionHolder());
                            }
                        }).thenReturn(con).onErrorResume(e -> {
                            if (txObject.isNewConnectionHolder()) {
                                return Mono.defer(()->{
                                            DataSourceUtils.releaseConnection(con, obtainDataSource());
                                            return Mono.empty();
                                        })
                                        .doOnTerminate(() -> txObject.setConnectionHolder(null, false))
                                        .then(Mono.error(e));
                            }
                            return Mono.error(e);
                        });
            }).onErrorResume(e -> {
                CannotCreateTransactionException ex = new CannotCreateTransactionException(
                        "Could not open JDBC Connection for transaction", e);
                return Mono.error(ex);
            });
        }).then();
    }

    /**
     * Determine the actual timeout to use for the given definition.
     * Will fall back to this manager's default timeout if the
     * transaction definition doesn't specify a non-default value.
     * @param definition the transaction definition
     * @return the actual timeout to use
     * @see org.springframework.transaction.TransactionDefinition#getTimeout()
     */
    protected Duration determineTimeout(TransactionDefinition definition) {
        if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
            return Duration.ofSeconds(definition.getTimeout());
        }
        return Duration.ZERO;
    }

    @Override
    protected Mono<Object> doSuspend(TransactionSynchronizationManager synchronizationManager, Object transaction)
            throws TransactionException {

        return Mono.defer(() -> {
            DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;
            txObject.setConnectionHolder(null);
            return Mono.justOrEmpty(synchronizationManager.unbindResource(obtainDataSource()));
        });
    }

    @Override
    protected Mono<Void> doResume(TransactionSynchronizationManager synchronizationManager,
                                  @Nullable Object transaction, Object suspendedResources) throws TransactionException {

        return Mono.defer(() -> {
            synchronizationManager.bindResource(obtainDataSource(), suspendedResources);
            return Mono.empty();
        });
    }

    @Override
    protected Mono<Void> doCommit(TransactionSynchronizationManager TransactionSynchronizationManager,
                                  GenericReactiveTransaction status) throws TransactionException {

        DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
        Connection connection = txObject.getConnectionHolder().getConnection();
        if (status.isDebug()) {
            logger.debug("Committing JDBC transaction on Connection [" + connection + "]");
        }
        return Mono.defer(()->{
                    try {
                        connection.commit();
                        return Mono.<Void>empty();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        return Mono.error(e);
                    }
        }).subscribeOn(MybatisScheduler.getScheduler())
                .onErrorMap(SQLException.class, ex -> translateException("JDBC commit", ex));
    }

    @Override
    protected Mono<Void> doRollback(TransactionSynchronizationManager TransactionSynchronizationManager,
                                    GenericReactiveTransaction status) throws TransactionException {

        DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
        Connection connection = txObject.getConnectionHolder().getConnection();
        if (status.isDebug()) {
            logger.debug("Rolling back JDBC transaction on Connection [" + connection + "]");
        }
        return Mono.defer(()->{
                    try {
                        connection.rollback();
                        return Mono.<Void>empty();
                    } catch (SQLException e) {
                        e.printStackTrace();
                        return Mono.error(e);
                    }
                }).subscribeOn(MybatisScheduler.getScheduler())
                .onErrorMap(SQLException.class, ex -> translateException("JDBC rollback", ex));
    }

    @Override
    protected Mono<Void> doSetRollbackOnly(TransactionSynchronizationManager synchronizationManager,
                                           GenericReactiveTransaction status) throws TransactionException {

        return Mono.fromRunnable(() -> {
            DataSourceTransactionObject txObject = (DataSourceTransactionObject) status.getTransaction();
            if (status.isDebug()) {
                logger.debug("Setting JDBC transaction [" + txObject.getConnectionHolder().getConnection() +
                        "] rollback-only");
            }
            txObject.setRollbackOnly();
        });
    }

    @Override
    protected Mono<Void> doCleanupAfterCompletion(TransactionSynchronizationManager synchronizationManager,
                                                  Object transaction) {

        return Mono.defer(() -> {
            DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;

            // Remove the connection holder from the context, if exposed.
            if (txObject.isNewConnectionHolder()) {
                synchronizationManager.unbindResource(obtainDataSource());
            }

            // Reset connection.
            Connection con = txObject.getConnectionHolder().getConnection();

            Mono<Void> afterCleanup = Mono.empty();

            if (txObject.isMustRestoreAutoCommit()) {
                afterCleanup = afterCleanup.then(
                        Mono.defer(()->{
                            try {
                                con.setAutoCommit(true);
                                return Mono.<Void>empty();
                            } catch (SQLException e) {
                                e.printStackTrace();
                                return Mono.error(e);
                            }
                        }).subscribeOn(MybatisScheduler.getScheduler()));
            }

            if (txObject.getPreviousIsolationLevel() != null) {
                afterCleanup = afterCleanup
                        .then(Mono.defer(()->{
                                    try {
                                        con.setTransactionIsolation(txObject.getPreviousIsolationLevel());
                                        return Mono.<Void>empty();
                                    } catch (SQLException e) {
                                        e.printStackTrace();
                                        return Mono.error(e);
                                    }
                                }).subscribeOn(MybatisScheduler.getScheduler()));
            }

            return afterCleanup.then(Mono.defer(() -> {
                try {
                    if (txObject.isNewConnectionHolder()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Releasing JDBC Connection [" + con + "] after transaction");
                        }
                        DataSourceUtils.releaseConnection(con, obtainDataSource());
                        return Mono.<Void>empty();
                    }
                }
                finally {
                    txObject.getConnectionHolder().clear();
                }
                return Mono.empty();
            }).subscribeOn(MybatisScheduler.getScheduler()));
        });
    }

    /**
     * Prepare the transactional {@link Connection} right after transaction begin.
     * <p>The default implementation executes a "SET TRANSACTION READ ONLY" statement if the
     * {@link #setEnforceReadOnly "enforceReadOnly"} flag is set to {@code true} and the
     * transaction definition indicates a read-only transaction.
     * <p>The "SET TRANSACTION READ ONLY" is understood by Oracle, MySQL and Postgres
     * and may work with other databases as well. If you'd like to adapt this treatment,
     * override this method accordingly.
     * @param con the transactional JDBC Connection
     * @param definition the current transaction definition
     * @param transaction the transaction object
     * @see #setEnforceReadOnly
     */
    protected Mono<Void> prepareTransactionalConnection(
            Connection con, TransactionDefinition definition, Object transaction) {

        DataSourceTransactionObject txObject = (DataSourceTransactionObject) transaction;

        Mono<Void> prepare = Mono.empty();

        if (isEnforceReadOnly() && definition.isReadOnly()) {
            prepare = Mono.defer(()->{
                                try (Statement stmt = con.createStatement()) {
                                    stmt.executeUpdate("SET TRANSACTION READ ONLY");
                                    return Mono.empty();
                                }catch (SQLException e){
                                    return Mono.error(e);
                                }
                            }).subscribeOn(MybatisScheduler.getScheduler())
                    .then();
        }

        // Apply specific isolation level, if any.
        //Integer isolationLevelToUse = resolveIsolationLevel(definition.getIsolationLevel());
        Integer isolationLevelToUse = definition.getIsolationLevel();
        if (isolationLevelToUse != null && definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {

            if (logger.isDebugEnabled()) {
                logger.debug("Changing isolation level of JDBC Connection [" + con + "] to " + isolationLevelToUse);
            }
            Mono<Void> finalPrepare = prepare;
            prepare = Mono.defer(()->{
                try {
                    Integer currentIsolation = con.getTransactionIsolation();
                    if (!currentIsolation.equals(isolationLevelToUse)) {
                        txObject.setPreviousIsolationLevel(currentIsolation);
                        return finalPrepare.then(Mono.defer(()->{
                            try {
                                con.setTransactionIsolation(isolationLevelToUse);
                                return Mono.empty();
                            } catch (SQLException e) {
                                return Mono.error(e);
                            }
                        }));
                    }else {
                        return finalPrepare;
                    }
                } catch (SQLException e) {
                    return Mono.error(e);
                }
            }).subscribeOn(MybatisScheduler.getScheduler());

        }

        // Switch to manual commit if necessary. This is very expensive in some JDBC drivers,
        // so we don't want to do it unnecessarily (for example if we've explicitly
        // configured the connection pool to set it already).
        prepare = prepare.then(Mono.defer(()->{
            try {
                if (con.getAutoCommit()) {
                    txObject.setMustRestoreAutoCommit(true);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Switching JDBC Connection [" + con + "] to manual commit");
                    }
                    return Mono.defer(()->{
                        try {
                            con.setAutoCommit(false);
                            return Mono.<Void>empty();
                        } catch (SQLException e) {
                            return Mono.error(e);
                        }
                    });
                }
                return Mono.empty();
            } catch (SQLException e) {
                return Mono.error(e);
            }
        }).subscribeOn(MybatisScheduler.getScheduler()));

        return prepare;
    }


    /**
     * Translate the given JDBC commit/rollback exception to a common Spring exception to propagate
     * from the {@link #commit}/{@link #rollback} call.
     * @param task the task description (commit or rollback).
     * @param ex the SQLException thrown from commit/rollback.
     * @return the translated exception to emit
     */
    protected RuntimeException translateException(String task, SQLException ex) {
        return new TransactionSystemException(task + " failed", ex);
    }


    /**
     * DataSource transaction object, representing a ConnectionHolder.
     * Used as transaction object by MybatisReactiveTransactionManager.
     */
    private static class DataSourceTransactionObject {

        @Nullable
        private ConnectionHolder connectionHolder;

        @Nullable
        private Integer previousIsolationLevel;

        private boolean newConnectionHolder;

        private boolean mustRestoreAutoCommit;

        void setConnectionHolder(@Nullable ConnectionHolder connectionHolder, boolean newConnectionHolder) {
            setConnectionHolder(connectionHolder);
            this.newConnectionHolder = newConnectionHolder;
        }

        boolean isNewConnectionHolder() {
            return this.newConnectionHolder;
        }

        void setRollbackOnly() {
            getConnectionHolder().setRollbackOnly();
        }

        public void setConnectionHolder(@Nullable ConnectionHolder connectionHolder) {
            this.connectionHolder = connectionHolder;
        }

        public ConnectionHolder getConnectionHolder() {
            Assert.state(this.connectionHolder != null, "No ConnectionHolder available");
            return this.connectionHolder;
        }

        public boolean hasConnectionHolder() {
            return (this.connectionHolder != null);
        }

        public void setPreviousIsolationLevel(@Nullable Integer previousIsolationLevel) {
            this.previousIsolationLevel = previousIsolationLevel;
        }

        @Nullable
        public Integer getPreviousIsolationLevel() {
            return this.previousIsolationLevel;
        }

        public void setMustRestoreAutoCommit(boolean mustRestoreAutoCommit) {
            this.mustRestoreAutoCommit = mustRestoreAutoCommit;
        }

        public boolean isMustRestoreAutoCommit() {
            return this.mustRestoreAutoCommit;
        }
    }

}
