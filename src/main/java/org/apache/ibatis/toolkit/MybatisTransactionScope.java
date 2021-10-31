package org.apache.ibatis.toolkit;

import org.springframework.transaction.reactive.TransactionSynchronizationManager;

public class MybatisTransactionScope {
    public static ThreadLocal<TransactionSynchronizationManager> transactionSynchronizationManagerThreadLocal = new ThreadLocal<>();

    public static TransactionSynchronizationManager getTransactionSynchronizationManager(){
        return transactionSynchronizationManagerThreadLocal.get();
    }

    public static void setTransactionSynchronizationManager(TransactionSynchronizationManager transactionSynchronizationManager){
        transactionSynchronizationManagerThreadLocal.set(transactionSynchronizationManager);
    }

    public static void clearTransactionSynchronizationManager(){
        transactionSynchronizationManagerThreadLocal.remove();
    }

    public static boolean hasTransactionSynchronizationManager(){
        return getTransactionSynchronizationManager() != null;
    }

    public static Object getTransactionSynchronizationManagerResource(Object key){
        TransactionSynchronizationManager transactionSynchronizationManager = getTransactionSynchronizationManager();
        if(transactionSynchronizationManager!=null){
            Object resource = transactionSynchronizationManager.getResource(key);
            return resource;
        }
        return null;
    }
}
