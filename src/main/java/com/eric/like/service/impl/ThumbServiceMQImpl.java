package com.eric.like.service.impl;

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.like.constant.RedisLuaScriptConstant;
import com.eric.like.constant.ThumbConstant;
import com.eric.like.listener.thumb.msg.ThumbEvent;
import com.eric.like.mapper.ThumbMapper;
import com.eric.like.model.dto.thumb.DoThumbRequest;
import com.eric.like.model.entity.Thumb;
import com.eric.like.model.entity.User;
import com.eric.like.model.enums.LuaStatusEnum;
import com.eric.like.service.ThumbService;
import com.eric.like.service.UserService;
import com.eric.like.util.RedisKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Service("thumbService")
@Slf4j
@RequiredArgsConstructor
public class ThumbServiceMQImpl extends ServiceImpl<ThumbMapper, Thumb>
        implements ThumbService {
  
    private final UserService userService;
  
    private final RedisTemplate<String, Object> redisTemplate;
  
    private final PulsarTemplate<ThumbEvent> pulsarTemplate;
  
    @Override  
    public Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        Assert.notNull(doThumbRequest, "参数错误");
        Assert.notNull(doThumbRequest.getBlogId(), "参数错误");
        User loginUser = userService.getLoginUser(request);
        if (Objects.isNull(loginUser)) {
            throw new RuntimeException("用户未登录");
        }
        Long loginUserId = loginUser.getId();  
        Long blogId = doThumbRequest.getBlogId();  
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);
        // 执行 Lua 脚本，点赞存入 Redis  
        long result = redisTemplate.execute(  
                RedisLuaScriptConstant.THUMB_SCRIPT_MQ,
                List.of(userThumbKey),
                blogId,
                //todo 暂时传一个大于当前时间30天后的时间戳，待思考是否需要替换成博客的创建时间的30天的时间戳
                Instant.now().plus(30, ChronoUnit.DAYS).toEpochMilli()
        );  
        if (LuaStatusEnum.FAIL.getValue() == result) {
            throw new RuntimeException("用户已点赞");  
        }  
  
        ThumbEvent thumbEvent = ThumbEvent.builder()  
                .blogId(blogId)  
                .userId(loginUserId)  
                .type(ThumbEvent.EventType.INCR)  
                .eventTime(LocalDateTime.now())
                .build();  
        pulsarTemplate.sendAsync(ThumbConstant.THUMB_TOPIC, thumbEvent).exceptionally(ex -> {
            redisTemplate.opsForHash().delete(userThumbKey, blogId.toString(), true);  
            log.error("点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);  
            return null;  
        });  
  
        return true;  
    }  
  
    @Override  
    public Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {  
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {  
            throw new RuntimeException("参数错误");  
        }  
        User loginUser = userService.getLoginUser(request);
        if (Objects.isNull(loginUser)) {
            throw new RuntimeException("用户未登录");
        }
        Long loginUserId = loginUser.getId();  
        Long blogId = doThumbRequest.getBlogId();  
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUserId);  
        // 执行 Lua 脚本，点赞记录从 Redis 删除  
        long result = redisTemplate.execute(  
                RedisLuaScriptConstant.UNTHUMB_SCRIPT_MQ,  
                List.of(userThumbKey),  
                blogId  
        );  
        if (LuaStatusEnum.FAIL.getValue() == result) {  
            throw new RuntimeException("用户未点赞");  
        }  
        ThumbEvent thumbEvent = ThumbEvent.builder()  
                .blogId(blogId)  
                .userId(loginUserId)  
                .type(ThumbEvent.EventType.DECR)  
                .eventTime(LocalDateTime.now())  
                .build();  
        pulsarTemplate.sendAsync(ThumbConstant.THUMB_TOPIC, thumbEvent).exceptionally(ex -> {
            redisTemplate.opsForHash().put(userThumbKey, blogId.toString(), true);  
            log.error("点赞事件发送失败: userId={}, blogId={}", loginUserId, blogId, ex);  
            return null;  
        });  
  
        return true;  
    }  
  
    @Override  
    public Boolean hasThumb(Long blogId, Long userId) {  
        //return redisTemplate.opsForHash().hasKey(RedisKeyUtil.getUserThumbKey(userId), blogId.toString());

        String userThumbKey = RedisKeyUtil.getUserThumbKey(userId);
        //redis版本不支持，所以使用了下面的方式，再RedisLuaScriptConstant.THUMB_SCRIPT的lua脚本里面有说明
        //ThumbRedisInfo thumbRedisInfo = (ThumbRedisInfo)redisTemplate.opsForHash().get(userThumbKey, blogId.toString());
        Long expireTime = (Long) redisTemplate.opsForHash().get(userThumbKey, blogId.toString());
        //如果对象为空，则查询数据库数据
        if(Objects.isNull(expireTime)){
            return this.lambdaQuery()
                    .eq(Thumb::getUserId, userId)
                    .eq(Thumb::getBlogId, blogId)
                    .exists();
        }
        //如果不为空，则判断redis key是否过期，过期则用jdk21的异步线程删除redis key
        if(expireTime < System.currentTimeMillis()){
            Thread.startVirtualThread(() -> {
                redisTemplate.opsForHash().delete(userThumbKey, blogId.toString());
            });
            return false;
        }
        //到这里表示redis key存在且key没有过期，则直接返回true
        return true;

    }  
  
}
