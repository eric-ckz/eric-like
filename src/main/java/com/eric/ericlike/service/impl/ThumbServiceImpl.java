package com.eric.ericlike.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.eric.ericlike.constant.ThumbConstant;
import com.eric.ericlike.mapper.ThumbMapper;
import com.eric.ericlike.model.dto.thumb.DoThumbRequest;
import com.eric.ericlike.model.entity.Blog;
import com.eric.ericlike.model.entity.Thumb;
import com.eric.ericlike.model.entity.User;
import com.eric.ericlike.service.BlogService;
import com.eric.ericlike.service.ThumbService;
import com.eric.ericlike.service.UserService;
import com.eric.ericlike.util.RedisKeyUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

@Service
public class ThumbServiceImpl extends ServiceImpl<ThumbMapper, Thumb> implements ThumbService {

    private final UserService userService;

    private final BlogService blogService;

    private final TransactionTemplate transactionTemplate;

    private final RedisTemplate<String, Object> redisTemplate;

    public ThumbServiceImpl(UserService userService, BlogService blogService, TransactionTemplate transactionTemplate, RedisTemplate<String, Object> redisTemplate) {
        this.userService = userService;
        this.blogService = blogService;
        this.transactionTemplate = transactionTemplate;
        this.redisTemplate = redisTemplate;
    }

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
                    redisTemplate.opsForHash().put(
                            ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId(),
                            blogId.toString(),
                            thumb.getId()
                    );
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
                // 更新点赞数
                boolean update = blogService.lambdaUpdate()
                        .eq(Blog::getId, blogId)
                        .setSql("thumbCount = thumbCount - 1")
                        .update();
                // 删除点赞数据
                boolean success = update && this.removeById(thumbId);
                if (success) {
                    redisTemplate.opsForHash().delete(ThumbConstant.USER_THUMB_KEY_PREFIX + loginUser.getId(), blogId.toString());
                }
                return success;
            });
        }
    }

    @Override
    public Boolean hasThumb(Long blogId, Long userId) {
        return redisTemplate.opsForHash().hasKey(RedisKeyUtil.getUserThumbKey(userId), blogId.toString());
    }

}
