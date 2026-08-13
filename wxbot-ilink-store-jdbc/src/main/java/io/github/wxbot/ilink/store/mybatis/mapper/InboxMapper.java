/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.mapper;

import io.github.wxbot.ilink.store.mybatis.entity.InboxEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 可靠收件箱 MyBatis-Plus Mapper。 */
public interface InboxMapper {
    /** 写入一条加密消息，数据库联合主键负责消息去重。 */
    @Insert("INSERT INTO wxbot_ilink_inbox(client_key,message_id,payload,created_at,attempt,"
            + "available_at,claimed_until,acknowledged,dead_letter) "
            + "VALUES(#{clientKey},#{messageId},#{payload},#{createdAt},#{attempt},"
            + "#{availableAt},#{claimedUntil},#{acknowledged},#{deadLetter})")
    int insertMessage(InboxEntity entity);

    /** 加锁读取当前可领取消息。 */
    @Select("SELECT client_key,message_id,payload,created_at,attempt,available_at,claimed_until,"
            + "acknowledged,dead_letter,failed_at,last_error FROM wxbot_ilink_inbox "
            + "WHERE client_key=#{clientKey} AND acknowledged=FALSE AND dead_letter=FALSE "
            + "AND available_at<=#{now} AND (claimed_until IS NULL OR claimed_until<=#{now}) "
            + "ORDER BY created_at,message_id LIMIT #{limit} FOR UPDATE")
    List<InboxEntity> selectClaimableForUpdate(@Param("clientKey") String clientKey,
            @Param("now") long now, @Param("limit") int limit);

    /** 设置消息领取截止时间。 */
    @Update("UPDATE wxbot_ilink_inbox SET claimed_until=#{claimedUntil} "
            + "WHERE client_key=#{clientKey} AND message_id=#{messageId}")
    int claim(@Param("clientKey") String clientKey, @Param("messageId") long messageId,
            @Param("claimedUntil") long claimedUntil);

    /** 确认消息处理完成。 */
    @Update("UPDATE wxbot_ilink_inbox SET acknowledged=TRUE,claimed_until=NULL "
            + "WHERE client_key=#{clientKey} AND message_id=#{messageId}")
    int acknowledge(@Param("clientKey") String clientKey, @Param("messageId") long messageId);

    /** 释放尚未确认的消息。 */
    @Update("UPDATE wxbot_ilink_inbox SET claimed_until=NULL WHERE client_key=#{clientKey} "
            + "AND message_id=#{messageId} AND acknowledged=FALSE")
    int release(@Param("clientKey") String clientKey, @Param("messageId") long messageId);

    /** 增加尝试次数并安排下次投递。 */
    @Update("UPDATE wxbot_ilink_inbox SET attempt=attempt+1,available_at=#{availableAt},"
            + "claimed_until=NULL,last_error=#{reason} WHERE client_key=#{clientKey} "
            + "AND message_id=#{messageId} AND acknowledged=FALSE")
    int markForRetry(@Param("clientKey") String clientKey, @Param("messageId") long messageId,
            @Param("availableAt") long availableAt, @Param("reason") String reason);

    /** 把处理失败的消息转入死信状态。 */
    @Update("UPDATE wxbot_ilink_inbox SET dead_letter=TRUE,claimed_until=NULL,"
            + "failed_at=#{failedAt},last_error=#{reason} WHERE client_key=#{clientKey} "
            + "AND message_id=#{messageId} AND acknowledged=FALSE")
    int deadLetter(@Param("clientKey") String clientKey, @Param("messageId") long messageId,
            @Param("reason") String reason, @Param("failedAt") long failedAt);

    /** 按失败时间读取死信。 */
    @Select("SELECT client_key,message_id,payload,created_at,attempt,available_at,claimed_until,"
            + "acknowledged,dead_letter,failed_at,last_error FROM wxbot_ilink_inbox "
            + "WHERE client_key=#{clientKey} AND dead_letter=TRUE "
            + "ORDER BY failed_at,message_id LIMIT #{limit}")
    List<InboxEntity> selectDeadLetters(@Param("clientKey") String clientKey,
            @Param("limit") int limit);

    /** 统计尚未确认且未进入死信的消息。 */
    @Select("SELECT COUNT(*) FROM wxbot_ilink_inbox WHERE client_key=#{clientKey} "
            + "AND acknowledged=FALSE AND dead_letter=FALSE")
    long countPending(@Param("clientKey") String clientKey);

    /** 删除指定客户端的全部消息。 */
    @Delete("DELETE FROM wxbot_ilink_inbox WHERE client_key=#{clientKey}")
    int deleteByClientKey(@Param("clientKey") String clientKey);
}
