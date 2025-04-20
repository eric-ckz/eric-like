package com.eric.like.job;

import com.eric.like.constant.ThumbConstant;
import com.eric.like.listener.thumb.msg.ThumbEvent;
import com.eric.like.model.entity.Thumb;
import com.eric.like.service.ThumbService;
import com.google.common.collect.Sets;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MQ对账任务 定时处理
 */
@Component
@Slf4j
public class ThumbReconcileJob {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private ThumbService thumbService;

    @Resource
    private PulsarTemplate<ThumbEvent> pulsarTemplate;

    /**
     * 定时任务入口（每天凌晨2点执行）
     * 该函数用于执行每日的对账任务，主要功能是比对Redis和MySQL中的数据差异，并发送补偿事件。
     * 任务执行流程如下：
     * 1. 从Redis中获取所有用户的点赞记录。
     * 2. 逐用户比对Redis和MySQL中的点赞记录。
     * 3. 计算Redis中有但MySQL中无的差异记录。
     * 4. 发送补偿事件以处理差异记录。
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void run() {
        long startTime = System.currentTimeMillis();

        // 1. 获取该分片下的所有用户ID
        Set<Long> userIds = new HashSet<>();
        //todo 每次对账都会取出 Redis 中所有的点赞记录，这样的效果不好，需要优化
        // 思路：消费消息时在 Redis 中额外记录一份要对账的数据，并在对账后删除
        // 优化对账任务，比如使用多线程提高效率
        String pattern = ThumbConstant.USER_THUMB_KEY_PREFIX + "*";
        try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long userId = Long.valueOf(key.replace(ThumbConstant.USER_THUMB_KEY_PREFIX, ""));
                userIds.add(userId);
            }
        }

        // 2. 逐用户比对Redis和MySQL中的点赞记录
        userIds.forEach(userId -> {
            // 从Redis中获取该用户的点赞记录
            Set<Long> redisBlogIds = redisTemplate.opsForHash().keys(ThumbConstant.USER_THUMB_KEY_PREFIX + userId).stream().map(obj -> Long.valueOf(obj.toString())).collect(Collectors.toSet());

            // 从MySQL中获取该用户的点赞记录
            Set<Long> mysqlBlogIds = Optional.ofNullable(thumbService.lambdaQuery()
                            .eq(Thumb::getUserId, userId)
                            .list()
                    ).orElse(new ArrayList<>())
                    .stream()
                    .map(Thumb::getBlogId)
                    .collect(Collectors.toSet());

            // 3. 计算Redis中有但MySQL中无的差异记录
            Set<Long> diffBlogIds = Sets.difference(redisBlogIds, mysqlBlogIds);

            // 4. 发送补偿事件以处理差异记录
            sendCompensationEvents(userId, diffBlogIds);
        });

        // 记录任务执行耗时
        log.info("对账任务完成，耗时 {}ms", System.currentTimeMillis() - startTime);
    }

    /**
     * 发送补偿事件到Pulsar
     */
    private void sendCompensationEvents(Long userId, Set<Long> blogIds) {
        blogIds.forEach(blogId -> {
            ThumbEvent thumbEvent = new ThumbEvent(userId, blogId, ThumbEvent.EventType.INCR, LocalDateTime.now());
            pulsarTemplate.sendAsync(ThumbConstant.THUMB_TOPIC, thumbEvent)
                    .exceptionally(ex -> {
                        log.error("补偿事件发送失败: userId={}, blogId={}", userId, blogId, ex);
                        return null;
                    });
        });
    }

}