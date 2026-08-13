/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.wxbot.ilink.store.mybatis.entity.CursorEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 消息游标 MyBatis-Plus Mapper。 */
public interface CursorMapper extends BaseMapper<CursorEntity> {
    /** 加锁读取游标，保证消息落库和游标推进的原子性。 */
    @Select("SELECT client_key,cursor_value FROM wxbot_ilink_cursor "
            + "WHERE client_key=#{clientKey} FOR UPDATE")
    CursorEntity selectForUpdate(@Param("clientKey") String clientKey);

    /** 推进指定客户端的消息游标。 */
    @Update("UPDATE wxbot_ilink_cursor SET cursor_value=#{cursorValue} "
            + "WHERE client_key=#{clientKey}")
    int updateCursor(@Param("clientKey") String clientKey,
            @Param("cursorValue") String cursorValue);

    /** 删除指定客户端的游标。 */
    @Delete("DELETE FROM wxbot_ilink_cursor WHERE client_key=#{clientKey}")
    int deleteByClientKey(@Param("clientKey") String clientKey);
}
