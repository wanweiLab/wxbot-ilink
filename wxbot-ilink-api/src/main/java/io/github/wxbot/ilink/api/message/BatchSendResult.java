/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 多次协议发送的部分成功结果。
 *
 * <p>批次按照请求顺序执行，遇到失败即停止；调用方可依据成功回执数量决定是否补偿，不会把“文本成功但媒体
 * 失败”误报为整体成功。
 *
 * @param successfulReceipts 已成功的有序回执
 * @param failedIndex 首个失败请求下标，全部成功时为 -1
 * @param failure 首个失败原因，全部成功时为空
 */
public record BatchSendResult(
        List<SendReceipt> successfulReceipts,
        int failedIndex,
        Throwable failure) {

    public BatchSendResult {
        successfulReceipts = List.copyOf(Objects.requireNonNull(successfulReceipts, "成功回执不能为空"));
        if (failedIndex < -1) {
            throw new IllegalArgumentException("失败下标不能小于 -1");
        }
        if ((failedIndex == -1) != (failure == null)) {
            throw new IllegalArgumentException("失败下标和失败原因必须同时存在或同时为空");
        }
    }

    /** @return 所有请求是否全部成功 */
    public boolean allSucceeded() {
        return failure == null;
    }

    /** @return 可选失败原因 */
    public Optional<Throwable> failureOptional() {
        return Optional.ofNullable(failure);
    }
}
