/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.wxbot.ilink.store.mybatis.entity.SnapshotEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 会话快照 MyBatis-Plus Mapper。 */
public interface SnapshotMapper extends BaseMapper<SnapshotEntity> {
    /** 原子更新已有快照。 */
    @Update("UPDATE wxbot_ilink_snapshot SET payload=#{payload},saved_at=#{savedAt} WHERE client_key=#{clientKey}")
    int updateSnapshot(@Param("clientKey") String clientKey,
            @Param("payload") byte[] payload, @Param("savedAt") long savedAt);
    /** 删除指定客户端的快照。 */
    @Delete("DELETE FROM wxbot_ilink_snapshot WHERE client_key=#{clientKey}")
    int deleteByClientKey(@Param("clientKey") String clientKey);
}
