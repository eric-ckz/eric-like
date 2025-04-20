package com.eric.like.job;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.text.StrPool;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eric.like.mapper.BlogMapper;
import com.eric.like.model.entity.Thumb;
import com.eric.like.model.enums.ThumbTypeEnum;
import com.eric.like.service.ThumbService;
import com.eric.like.util.RedisKeyUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 定时将Redis中的临时点赞数据同步到数据库
 */
@Component
@Slf4j
public class SyncThumb2DBJob {

    @Resource
    private ThumbService thumbService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Async("taskExecutor")  // 标记为异步方法同时指定自定义线程池
    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    @Transactional(rollbackFor = Exception.class)
    public void run() {
        log.info("开始执行");
        String date = getTimeSlice();
        syncThumb2DBByDate(date);
        log.info("临时数据同步完成");
    }

    public void syncThumb2DBByDate(String date) {
        // 获取到临时点赞和取消点赞数据
        String tempThumbKey = RedisKeyUtil.getTempThumbKey(date);
        Map<Object, Object> allTempThumbMap = redisTemplate.opsForHash().entries(tempThumbKey);
        boolean thumbMapEmpty = CollUtil.isEmpty(allTempThumbMap);
        if (thumbMapEmpty) {
            log.info("临时点赞数据为空");
            return;
        }

        // 同步 点赞 到数据库
        // 构建插入列表并收集blogId
        Map<Long, Long> blogThumbCountMap = new HashMap<>();
        List<Thumb> thumbList = new ArrayList<>();
        LambdaQueryWrapper<Thumb> wrapper = new LambdaQueryWrapper<>();
        boolean needRemove = false;
        for (Object userIdBlogIdObj : allTempThumbMap.keySet()) {
            String userIdBlogId = (String) userIdBlogIdObj;
            String[] userIdAndBlogId = userIdBlogId.split(StrPool.COLON);
            Long userId = Long.valueOf(userIdAndBlogId[0]);
            Long blogId = Long.valueOf(userIdAndBlogId[1]);
            // -1 取消点赞，1 点赞
            Integer thumbType = Integer.valueOf(allTempThumbMap.get(userIdBlogId).toString());
            if (thumbType == ThumbTypeEnum.INCR.getValue()) {
                Thumb thumb = new Thumb();
                thumb.setUserId(userId);
                thumb.setBlogId(blogId);
                thumbList.add(thumb);
            } else if (thumbType == ThumbTypeEnum.DECR.getValue()) {
                // 拼接查询条件，批量删除
                needRemove = true;
                wrapper.or().eq(Thumb::getUserId, userId).eq(Thumb::getBlogId, blogId);
            } else {
                if (thumbType != ThumbTypeEnum.NON.getValue()) {
                    log.warn("数据异常：{}", userId + "," + blogId + "," + thumbType);
                }
                continue;
            }
            // 计算点赞增量
            blogThumbCountMap.put(blogId, blogThumbCountMap.getOrDefault(blogId, 0L) + thumbType);
        }
        // 批量插入
        if (CollectionUtil.isNotEmpty(thumbList)) {
            log.info("批量插入点赞记录");
            thumbService.saveBatch(thumbList);
        }
        // 批量删除
        if (needRemove) {
            log.info("批量删除点赞记录");
            thumbService.remove(wrapper);
        }
        // 批量更新博客点赞量
        if (!blogThumbCountMap.isEmpty()) {
            log.info("批量更新点赞量");
            blogMapper.batchUpdateThumbCount(blogThumbCountMap);
        }
        // 异步删除
        Thread.startVirtualThread(() -> {
            log.info("异步删除点赞数据");
            redisTemplate.delete(tempThumbKey);
        });
    }

    /**
     * 获取时间片
     */
    private String getTimeSlice() {
        DateTime nowDate = DateUtil.date();
        // 获取到当前时间前最近的整数秒，比如当前 11:20:23 ，获取到 11:20:20
        return DateUtil.format(nowDate, "HH:mm:") + (DateUtil.second(nowDate) / 10) * 10;
    }

}
