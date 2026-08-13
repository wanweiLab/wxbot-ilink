/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.transport;

import io.github.wxbot.ilink.api.media.MediaUploadGrant;
import io.github.wxbot.ilink.api.media.MediaUploadRequest;
import io.github.wxbot.ilink.api.session.BotSession;

import java.util.concurrent.CompletionStage;

/** iLink 媒体控制面协议，不负责 CDN 数据流传输。 */
public interface MediaProtocol {
    /** 申请上传授权。 */
    CompletionStage<MediaUploadGrant> requestUpload(
            BotSession session, MediaUploadRequest request);
}
