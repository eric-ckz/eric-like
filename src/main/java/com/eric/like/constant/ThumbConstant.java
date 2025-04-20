package com.eric.like.constant;

/**
 * 点赞相关常量类
 */
public interface ThumbConstant {

    /**
     * 用户点赞 hash key
     */
    String USER_THUMB_KEY_PREFIX = "thumb:";
    /**
     * 临时 点赞记录 key
     */
    String TEMP_THUMB_KEY_PREFIX = "thumb:temp:%s";

    Long UN_THUMB_CONSTANT = 0L;

    String THUMB_TOPIC = "thumb-topic";
}
