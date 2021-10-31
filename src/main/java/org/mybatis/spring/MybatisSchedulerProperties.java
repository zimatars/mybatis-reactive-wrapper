package org.mybatis.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mybatis.scheduler")
public class MybatisSchedulerProperties {
    private int threadCap;
    private int queuedTaskCap;
    private String name;

    public MybatisSchedulerProperties() {
        this.threadCap = 20;
        this.queuedTaskCap = 100;
        this.name = "mybatis-thread";
    }

    public int getThreadCap() {
        return threadCap;
    }

    public void setThreadCap(int threadCap) {
        this.threadCap = threadCap;
    }

    public int getQueuedTaskCap() {
        return queuedTaskCap;
    }

    public void setQueuedTaskCap(int queuedTaskCap) {
        this.queuedTaskCap = queuedTaskCap;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
