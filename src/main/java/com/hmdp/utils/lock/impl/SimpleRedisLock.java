package com.hmdp.utils.lock.impl;


import cn.hutool.core.lang.UUID;
import com.hmdp.utils.lock.Lock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author ghp
 * @title
 * @description
 */
public class SimpleRedisLock implements Lock {

    /**
     * RedisTemplate
     */
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 锁的名称
     */
    private String name;
    /**
     * key前缀
     */
    public static final String KEY_PREFIX = "lock:";
    /**
     * ID前缀
     */
    public static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;


    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }


    /**
     * 获取锁
     *
     * @param timeoutSec 超时时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId() + "";
        // SET lock:name id EX timeoutSec NX
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        //使用lua脚本

        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());

//        // 判断 锁的线程标识 是否与 当前线程一致
//        String currentThreadFlag = ID_PREFIX + Thread.currentThread().getId();
//        String redisThreadFlag = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (currentThreadFlag != null || currentThreadFlag.equals(redisThreadFlag)) {
//            // 一致，说明当前的锁就是当前线程的锁，可以直接释放
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//        // 不一致，不能释放
    }
}
