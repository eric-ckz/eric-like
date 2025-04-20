package com.eric.like.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.like.constant.ThumbConstant;
import com.eric.like.manager.cache.CacheManager;
import com.eric.like.mapper.ThumbMapper;
import com.eric.like.model.dto.thumb.DoThumbRequest;
import com.eric.like.model.entity.Blog;
import com.eric.like.model.entity.Thumb;
import com.eric.like.model.entity.User;
import com.eric.like.service.BlogService;
import com.eric.like.service.ThumbService;
import com.eric.like.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service("thumbServiceLocalCache")
@Slf4j
@RequiredArgsConstructor
public class ThumbServiceImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {

    private final UserService userService;

    private final BlogService blogService;

    private final TransactionTemplate transactionTemplate;

    private final RedisTemplate<String, Object> redisTemplate;

    // 引入缓存管理
    private final CacheManager cacheManager;

    @Override
    public Boolean doThumb(DoThumbRequest doThumbRequest, HttpServletRequest request) {
        if (doThumbRequest == null || doThumbRequest.getBlogId() == null) {
            throw new RuntimeException("参数错误");
        }
        User loginUser = userService.getLoginUser(request);
        if (Objects.isNull(loginUser)) {
            throw new RuntimeException("用户未登录");
        }
        // 加锁
        synchronized (loginUser.getId().toString().intern()) {

            // 编程式事务
            return transactionTemplate.execute(status -> {
                Long blogId = doThumbRequest.getBlogId();
                //todo 缓存过期问题， 使用冷热分离的方法：
                /**
                     可以调整 value 的数据结构，比如调整为:
                     ```json
                     {
                     "blogId":xxx,
                     "expireTime":xxx
                     }
                     ```
                     然后使用时在内存中判断是否过期，未过期就正常使用，如果过期，可以通过虚拟线程异步删除，或者通过消息队列删除
                 */
                //判斷是否已经点赞
                Boolean exists = this.hasThumb(blogId, loginUser.getId());

                if (exists) {
                    throw new RuntimeException("用户已点赞");
                }

                //更新点赞数
                boolean update = blogService.lambdaUpdate()
                        .eq(Blog::getId, blogId)
                        .setSql("thumbCount = thumbCount + 1")
                        .update();

                //保存点赞数据
                Thumb thumb = new Thumb();
                thumb.setUserId(loginUser.getId());
                thumb.setBlogId(blogId);
                // 更新成功才执行
                boolean success = update && this.save(thumb);
                // 点赞记录存入 Redis
                if (success) {
                    String hashKey = ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId();
                    String fieldKey = blogId.toString();
                    Long realThumbId = thumb.getId();
                    redisTemplate.opsForHash().put(hashKey, fieldKey, realThumbId);
                    cacheManager.putIfPresent(hashKey, fieldKey, realThumbId);
                }
                return success;
            });
        }
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
        // 加锁
        synchronized (loginUser.getId().toString().intern()) {

            // 编程式事务
            return transactionTemplate.execute(status -> {
                Long blogId = doThumbRequest.getBlogId();
                //判斷是否已经点赞
                Long thumbId = ((Long) redisTemplate.opsForHash().get(ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId(), blogId.toString()));
                if (thumbId == null) {
                    throw new RuntimeException("用户未点赞");
                }
                Object thumbIdObj = cacheManager.get(ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId(), blogId.toString());
                if (Objects.isNull(thumbIdObj) || ThumbConstant.UN_THUMB_CONSTANT.equals(thumbIdObj)) {
                    throw new RuntimeException("用户未点赞");
                }
                // 更新点赞数
                boolean update = blogService.lambdaUpdate()
                        .eq(Blog::getId, blogId)
                        .setSql("thumbCount = thumbCount - 1")
                        .update();
                // 删除点赞数据
                boolean success = update && this.removeById(thumbId);
                if (success) {
                    String hashKey = ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId();
                    String fieldKey = blogId.toString();
                    redisTemplate.opsForHash().delete(hashKey, fieldKey);
                    cacheManager.putIfPresent(hashKey, fieldKey, ThumbConstant.UN_THUMB_CONSTANT);
                }
                return success;
            });
        }
    }

    @Override
    public Boolean hasThumb(Long blogId, Long userId) {
        Object thumbIdObj = cacheManager.get(ThumbConstant.USER_THUMB_KEY_PREFIX + userId, blogId.toString()) != null;
        if (Objects.isNull(thumbIdObj)) {
            return false;
        }
        Long thumbId = (Long) thumbIdObj;
        //规定： 当值为 0 时代表当前未点赞
        return !Objects.equals(thumbId, ThumbConstant.UN_THUMB_CONSTANT);
    }

}
