package org.apache.ibatis.toolkit;

import org.mybatis.spring.MybatisSchedulerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class MybatisScheduler {
    public static volatile Scheduler scheduler = null;

    public static Scheduler getScheduler() {
        if (scheduler == null) {
            synchronized (MybatisScheduler.class) {
                if (scheduler == null){
                    MybatisSchedulerProperties properties = SpringContextUtil.getBean(MybatisSchedulerProperties.class);
                    scheduler =  Schedulers.newBoundedElastic(properties.getThreadCap(), properties.getQueuedTaskCap(),properties.getName());
                }
            }
        }
        return scheduler;
    }

}
