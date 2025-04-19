package com.eric.ericlike.constant;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * lua脚本常量类
 */
 public interface RedisLuaScriptConstant {

    /**
     * 点赞 Lua 脚本
     * KEYS[1]       -- 临时计数键
     * KEYS[2]       -- 用户点赞状态键
     * ARGV[1]       -- 用户 ID
     * ARGV[2]       -- 博客 ID
     * 返回:
     * -1: 已点赞
     * 1: 操作成功
     */
    RedisScript<Long> THUMB_SCRIPT = new DefaultRedisScript<>("""  
            local tempThumbKey = KEYS[1]       -- 临时计数键（如 thumb:temp:{timeSlice}）  
            local userThumbKey = KEYS[2]       -- 用户点赞状态键（如 thumb:{userId}）  
            local userId = ARGV[1]             -- 用户 ID  
            local blogId = ARGV[2]             -- 博客 ID  
            local expireTime = ARGV[3]             -- 过期时间  
              
            -- 1. 检查是否已点赞（避免重复操作）  
            if redis.call('HEXISTS', userThumbKey, blogId) == 1 then
                return -1  -- 已点赞，返回 -1 表示失败  
            end
              
            -- 2. 获取旧值（不存在则默认为 0）  
            local hashKey = userId .. ':' .. blogId
            local oldNumber = tonumber(redis.call('HGET', tempThumbKey, hashKey) or 0)
              
            -- 3. 计算新值  
            local newNumber = oldNumber + 1  
              
            -- 4. 原子性更新：写入临时计数 + 标记用户已点赞  
            redis.call('HSET', tempThumbKey, hashKey, newNumber)  
            -- 解释一下为什么存过期时间： 到了这一步证明已经代表要新增一个redis key表示已经点赞，内容一定是1，既然这样的话不如存一个过期时间，用的时候直接判断过期时间即可，一定是正常状态的
            -- redis.call('HSET', userThumbKey, blogId, 1)
            -- 再解释一下，做这个项目的时候使用的redis版本是5.0.14.1，不支持lua脚本的HMSET命令，所以没有用下面的HMSET命令
            -- redis.call('HMSET', userThumbKey, blogId,\s
            --     'luaStatus', 1,
            --     'expireTime', expireTime
            -- )
            redis.call('HSET', userThumbKey, blogId, expireTime)  
              
            return 1  -- 返回 1 表示成功  
            """, Long.class);

    /**
     * 取消点赞 Lua 脚本
     * 参数同上
     * 返回：
     * -1: 未点赞
     * 1: 操作成功
     */
    RedisScript<Long> UNTHUMB_SCRIPT = new DefaultRedisScript<>("""  
            local tempThumbKey = KEYS[1]      -- 临时计数键（如 thumb:temp:{timeSlice}）  
            local userThumbKey = KEYS[2]      -- 用户点赞状态键（如 thumb:{userId}）  
            local userId = ARGV[1]            -- 用户 ID  
            local blogId = ARGV[2]            -- 博客 ID  
              
            -- 1. 检查用户是否已点赞（若未点赞，直接返回失败）  
            if redis.call('HEXISTS', userThumbKey, blogId) ~= 1 then  
                return -1  -- 未点赞，返回 -1 表示失败  
            end  
              
            -- 2. 获取当前临时计数（若不存在则默认为 0）  
            local hashKey = userId .. ':' .. blogId  
            local oldNumber = tonumber(redis.call('HGET', tempThumbKey, hashKey) or 0)  
              
            -- 3. 计算新值并更新  
            local newNumber = oldNumber - 1  
              
            -- 4. 原子性操作：更新临时计数 + 删除用户点赞标记  
            redis.call('HSET', tempThumbKey, hashKey, newNumber)  
            redis.call('HDEL', userThumbKey, blogId)  
              
            return 1  -- 返回 1 表示成功  
            """, Long.class);

}
