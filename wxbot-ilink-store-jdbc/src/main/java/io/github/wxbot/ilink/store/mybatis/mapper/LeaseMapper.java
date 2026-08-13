/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.wxbot.ilink.store.mybatis.entity.LeaseEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 单活租约 MyBatis-Plus Mapper。 */
public interface LeaseMapper extends BaseMapper<LeaseEntity> {
    /** 加锁读取当前租约。 */
    @Select("SELECT * FROM wxbot_ilink_lease WHERE client_key=#{clientKey} FOR UPDATE")
    LeaseEntity selectForUpdate(@Param("clientKey") String clientKey);
    /** 接管或刷新租约。 */
    @Update("UPDATE wxbot_ilink_lease SET owner_id=#{ownerId},expires_at=#{expiresAt} WHERE client_key=#{clientKey}")
    int takeOver(@Param("clientKey") String clientKey,
            @Param("ownerId") String ownerId, @Param("expiresAt") long expiresAt);
    /** 仅允许活跃租约持有者续订。 */
    @Update("UPDATE wxbot_ilink_lease SET expires_at=#{expiresAt} WHERE client_key=#{clientKey} "
            + "AND owner_id=#{ownerId} AND expires_at>#{now}")
    int renew(@Param("clientKey") String clientKey, @Param("ownerId") String ownerId,
            @Param("now") long now, @Param("expiresAt") long expiresAt);
    /** 仅允许持有者释放租约。 */
    @Delete("DELETE FROM wxbot_ilink_lease WHERE client_key=#{clientKey} AND owner_id=#{ownerId}")
    int release(@Param("clientKey") String clientKey, @Param("ownerId") String ownerId);
    /** 删除指定客户端的租约。 */
    @Delete("DELETE FROM wxbot_ilink_lease WHERE client_key=#{clientKey}")
    int deleteByClientKey(@Param("clientKey") String clientKey);
}
