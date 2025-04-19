package com.eric.ericlike.service.impl;

import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.ericlike.constant.RedisLuaScriptConstant;
import com.eric.ericlike.mapper.ThumbMapper;
import com.eric.ericlike.model.dto.thumb.DoThumbRequest;
import com.eric.ericlike.model.dto.thumb.ThumbRedisInfo;
import com.eric.ericlike.model.entity.Thumb;
import com.eric.ericlike.model.entity.User;
import com.eric.ericlike.model.enums.LuaStatusEnum;
import com.eric.ericlike.service.ThumbService;
import com.eric.ericlike.service.UserService;
import com.eric.ericlike.util.RedisKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;

@Service("thumbService")
@Slf4j
@RequiredArgsConstructor
public class ThumbServiceRedisImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {
  
    private final UserService userService;
  
    private final RedisTemplate<String, Object> redisTemplate;
  
    @Override  
    public Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {  
            throw new RuntimeException("参数错误");  
        }  
        User loginUser = userService.getLoginUser(request);
        if (Objects.isNull(loginUser)) {
            throw new RuntimeException("用户未登录");
        }
        Long blogId = doThumbRequest.getBlogId();  
  
        String timeSlice = getTimeSlice();  
        // Redis Key  
        String tempThumbKey = RedisKeyUtil.getTempThumbKey(timeSlice);
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUser.getId());  
  
        // 执行 Lua 脚本  
        long epochMilli = Instant.now().plus(30, ChronoUnit.DAYS).toEpochMilli();
        log.info("epochMilli:{}", epochMilli);
        long result = redisTemplate.execute(
                RedisLuaScriptConstant.THUMB_SCRIPT,
                Arrays.asList(tempThumbKey, userThumbKey),
                loginUser.getId(),  
                blogId,
                //todo 暂时传一个大于当前时间30天后的时间戳，待思考是否需要替换成博客的创建时间的30天的时间戳
                epochMilli
        );

        if (LuaStatusEnum.FAIL.getValue() == result) {
            throw new RuntimeException("用户已点赞");  
        }  
  
        // 更新成功才执行  
        return LuaStatusEnum.SUCCESS.getValue() == result;  
    }  
  
    @Override  
    public Boolean undoThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {  
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {  
            throw new RuntimeException("参数错误");  
        }  
        User loginUser = userService.getLoginUser(request);  
        if (Objects.isNull(loginUser)){
            throw new RuntimeException("用户未登录");
        }
        Long blogId = doThumbRequest.getBlogId();  
        // 计算时间片  
        String timeSlice = getTimeSlice();  
        // Redis Key  
        String tempThumbKey = RedisKeyUtil.getTempThumbKey(timeSlice);  
        String userThumbKey = RedisKeyUtil.getUserThumbKey(loginUser.getId());  
  
        // 执行 Lua 脚本  
        long result = redisTemplate.execute(  
                RedisLuaScriptConstant.UNTHUMB_SCRIPT,  
                Arrays.asList(tempThumbKey, userThumbKey),  
                loginUser.getId(),  
                blogId  
        );  
        // 根据返回值处理结果  
        if (result == LuaStatusEnum.FAIL.getValue()) {  
            throw new RuntimeException("用户未点赞");  
        }  
        return LuaStatusEnum.SUCCESS.getValue() == result;  
    }

    /**
     * 获取时间片
     */
    private String getTimeSlice() {  
        DateTime nowDate = DateUtil.date();
        // 获取到当前时间前最近的整数秒，比如当前 11:20:23 ，获取到 11:20:20  
        return DateUtil.format(nowDate, "HH:mm:") + (DateUtil.second(nowDate) / 10) * 10;  
    }  
  
    @Override  
    public Boolean hasThumb(Long blogId, Long userId) {
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
