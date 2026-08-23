package com.mhp.booksystem.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-Job 是什么？
 *   XXL-Job 是一个"分布式任务调度平台"，由两部分组成：
 *     - 调度中心（Admin）：独立的 Web 应用（localhost:8080/xxl-job-admin），负责在指定时间触发任务、
 *       记录执行日志、提供 Web 界面查看结果、支持手动触发和停止。
 *     - 执行器（Executor）：就是我们自己的 Spring Boot 应用，向调度中心注册自己，
 *       调度中心到点了就 HTTP 调用执行器，执行器里的 @XxlJob 方法负责干活。
 *
 * 为什么不直接用 Spring 的 @Scheduled？
 *   @Scheduled 可以做定时任务，但有几个缺点：
 *     1. 没有 Web 界面：任务运行情况看不到，失败了不知道，只能翻日志
 *     2. 不能手动触发：想立刻跑一次只能重启应用或改代码
 *     3. 多实例问题：生产环境部署两台服务器时，每台都会跑一次，订单被取消两遍
 *   XXL-Job 解决了这三个问题：有 Web 控制台、可手动触发、调度中心保证同一时刻只通知一台执行器。
 *
 * 本项目任务（在 XXL-Job 控制台手动添加）：
 *   cancelTimeoutOrderJob — Cron: 0 * * * * ?（每分钟）取消超时未付款订单
 *   reminderJob           — Cron: 0 0 9 * * ?（每天早 9 点）发明日档期提醒
 */
@Slf4j
@Configuration
public class XxlJobConfig {

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    @Value("${xxl.job.admin.accessToken}")
    private String accessToken;

    @Value("${xxl.job.executor.appname}")
    private String appname;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port}")
    private int port;

    @Value("${xxl.job.executor.logpath}")
    private String logPath;

    @Value("${xxl.job.executor.logretentiondays}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);  // 调度中心地址，执行器启动后向它注册
        executor.setAccessToken(accessToken);         // 通信鉴权 token，调度中心和执行器要一致
        executor.setAppname(appname);                 // 执行器名称，在调度中心 Web 界面里显示
        executor.setIp(ip);                           // 留空则自动取本机 IP
        executor.setPort(port);                       // 调度中心回调执行器用的端口，本地用 9999
        executor.setLogPath(logPath);                 // 任务执行日志存放目录
        executor.setLogRetentionDays(logRetentionDays); // 日志保留天数，超期自动清理
        log.info("[XXL-JOB] 执行器启动，appname={} port={}", appname, port);
        return executor;
    }
}
