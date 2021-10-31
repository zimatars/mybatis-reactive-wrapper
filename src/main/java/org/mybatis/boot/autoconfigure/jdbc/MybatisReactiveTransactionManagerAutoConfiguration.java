package org.mybatis.boot.autoconfigure.jdbc;

import org.apache.ibatis.toolkit.MybatisScheduler;
import org.mybatis.spring.MybatisSchedulerProperties;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.MybatisReactiveTransactionManager;
import org.springframework.transaction.ReactiveTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ ReactiveTransactionManager.class })
@AutoConfigureBefore(TransactionAutoConfiguration.class)
@EnableConfigurationProperties({MybatisSchedulerProperties.class})
public class MybatisReactiveTransactionManagerAutoConfiguration {

    static {
        try {
            Class.forName("org.apache.ibatis.binding.MapperMethod");
            Class.forName("org.apache.ibatis.binding.MapperProxy");
            Class.forName("org.mybatis.spring.SqlSessionUtils");
            Class.forName("org.mybatis.spring.SqlSessionTemplate");
            Class.forName("org.springframework.jdbc.datasource.DataSourceUtils");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveTransactionManager.class)
    public ReactiveTransactionManager connectionFactoryTransactionManager(DataSource dataSource) {
        return new MybatisReactiveTransactionManager(dataSource);
    }

}
