package com.eric.ericlike.model.dto.thumb;

import lombok.Data;

/**
 * 点赞的redis缓存对象
 */
@Data
public class ThumbRedisInfo {

    /**
     * 值对应LuaStatusEnum类的值
     */
    private Integer luaStatus;

    /**
     * redis缓存过期时间
     */
    private Long expireTime;

}
