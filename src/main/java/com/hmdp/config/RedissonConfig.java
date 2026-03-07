package com.hmdp.config;

import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    public RedissonClient redissonClient() {
        // 1. 创建配置
        org.redisson.config.Config config = new org.redisson.config.Config();
        // 2. 添加地址, 这里使用单节点部署, 如果是集群部署,
        // 则使用config.useClusterServers().addNodeAddress("redis://ip:port")
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        // 3. 创建RedissonClient
        return org.redisson.Redisson.create(config);
    }
}
