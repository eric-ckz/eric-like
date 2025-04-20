package com.eric.like.listener.thumb;

import cn.hutool.core.lang.Pair;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eric.like.constant.ThumbConstant;
import com.eric.like.listener.thumb.msg.ThumbEvent;
import com.eric.like.mapper.BlogMapper;
import com.eric.like.model.entity.Thumb;
import com.eric.like.service.ThumbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.common.schema.SchemaType;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ThumbConsumer {

    private final BlogMapper blogMapper;

    private final ThumbService thumbService;

    /**
     * 批量处理MQ消息
     */
    @PulsarListener(
            subscriptionName = "thumb-subscription",
            topics = ThumbConstant.THUMB_TOPIC,
            schemaType = SchemaType.JSON,
            batch = true,
            subscriptionType = SubscriptionType.Shared,
            // consumerCustomizer = "thumbConsumerConfig",
            // 引用 NACK 重试策略
            negativeAckRedeliveryBackoff = "negativeAckRedeliveryBackoff",
            // 引用 ACK 超时重试策略
            ackTimeoutRedeliveryBackoff = "ackTimeoutRedeliveryBackoff",
            // 引用死信队列策略
            deadLetterPolicy = "deadLetterPolicy"
    )
    @Transactional(rollbackFor = Exception.class)
    public void processBatch(List<Message<ThumbEvent>> messages){
        log.info("ThumbConsumer processBatch: {}", messages.size());
        for (Message<ThumbEvent> message : messages) {
            log.info("message.getMessageId() = {}", message.getMessageId());
        }
        if(true){
            log.info("test=============");
            throw new RuntimeException("test");
        }

        Map<Long, Long> countMap = new ConcurrentHashMap<>();
        List<Thumb> thumbs = new ArrayList<>();

        // 并行处理消息
        LambdaQueryWrapper<Thumb> wrapper = new LambdaQueryWrapper<>();
        AtomicReference<Boolean> needRemove = new AtomicReference<>(false);

        //1、提取事件并过滤出无效消息
        List<ThumbEvent> events = messages.stream()
                .map(Message::getValue)
                .filter(Objects::nonNull)
                .toList();

        //2、按(userId, blogId)分组，并获取每个分组的最新事件
        Map<Pair<Long, Long>, ThumbEvent> latestEvents = events.stream()

                .collect(Collectors.groupingBy(
                        //Collectors.groupingBy之后的结果是一个map，key为分组的key，value为分组后的list
                        //分组的key
                        e -> Pair.of(e.getUserId(), e.getBlogId()),
                        //分组list的收集器
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> {
                                    // 按时间升序排序，取最后一个作为最新事件
                                    list.sort(Comparator.comparing(ThumbEvent::getEventTime));
                                    //如果为偶数的时候，代表这个用户对这篇博客点赞且又取消点赞，不需要做任何处理
                                    if (list.size() % 2 == 0) {
                                        return null;
                                    }
                                    //奇数的时候，代表这个用户对这篇博客点赞或者取消点赞，需要做处理
                                    return list.getLast();
                                }
                        )
                ));

        latestEvents.forEach((userBlogPair, event) -> {
            if (event == null) {
                return;
            }
            ThumbEvent.EventType finalAction = event.getType();
            //点赞
            if (finalAction == ThumbEvent.EventType.INCR) {
                countMap.merge(event.getBlogId(), 1L, Long::sum);
                Thumb thumb = new Thumb();
                thumb.setBlogId(event.getBlogId());
                thumb.setUserId(event.getUserId());
                thumbs.add(thumb);
            } else {
                //取消点赞
                needRemove.set(true);
                wrapper.or().eq(Thumb::getUserId, event.getUserId()).eq(Thumb::getBlogId, event.getBlogId());
                countMap.merge(event.getBlogId(), -1L, Long::sum);
            }
        });

        // 批量更新数据库
        if (needRemove.get()) {
            thumbService.remove(wrapper);
        }
        batchUpdateBlogs(countMap);
        batchInsertThumbs(thumbs);

    }


    public void batchUpdateBlogs(Map<Long, Long> countMap) {
        if (!countMap.isEmpty()) {
            blogMapper.batchUpdateThumbCount(countMap);
        }
    }

    public void batchInsertThumbs(List<Thumb> thumbs) {
        if (!thumbs.isEmpty()) {
            // 分批次插入
            thumbService.saveBatch(thumbs, 500);
        }
    }

    /**
     * 处理死信队列消息
     */
    @PulsarListener(topics = "thumb-dlq-topic")
    public void consumerDlq(Message<ThumbEvent> message){
        MessageId messageId = message.getMessageId();
        log.info("ThumbConsumer consumerDlq: {}", messageId);
        //todo 处理死信队列消息
    }

}
