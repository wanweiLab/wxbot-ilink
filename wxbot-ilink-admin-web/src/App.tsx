// Copyright 2026 wxbot-ilink contributors
// SPDX-License-Identifier: Apache-2.0
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert, Avatar, Button, Card, Descriptions, Form, Grid, Input, Layout, Menu,
  Message, Modal, PageHeader, Space, Statistic, Steps, Table, Tag, Typography,
} from '@arco-design/web-react';
import {
  IconApps, IconCheckCircle, IconCloud, IconDelete, IconExport, IconLink,
  IconMobile, IconPause, IconPlus, IconRefresh, IconRobot, IconSend,
} from '@arco-design/web-react/icon';
import { QRCodeSVG } from 'qrcode.react';
import {
  api, BotLoginPhase, BotRuntimeView, BotStatus, LoginResponse, LoginStatusResponse,
} from './api';

const { Header, Sider, Content } = Layout;
const { Row, Col } = Grid;
const FormItem = Form.Item;
const Step = Steps.Step;

const statusMeta: Record<BotStatus, { label: string; color: string }> = {
  LOGIN_REQUIRED: { label: '等待扫码', color: 'orange' },
  LOGIN_PENDING: { label: '扫码中', color: 'arcoblue' },
  ONLINE: { label: '在线', color: 'green' },
  OFFLINE: { label: '已停止', color: 'gray' },
  ERROR: { label: '异常', color: 'red' },
  DELETING: { label: '删除中', color: 'magenta' },
};

/** wxbot-ilink 管理前端入口。 */
export default function App() {
  if (isQrLayoutPreview()) {
    return <QrModal value={{ userId: 'preview-user', value: {
      attemptId: 'preview-attempt', imageContent: 'wxbot-ilink-layout-preview',
      expiresAt: '2026-08-13T05:02:18Z', phase: 'BOUND',
    } }} onCancel={() => undefined} onBound={async () => undefined}
      onRetry={async () => undefined} />;
  }
  return <AuthenticatedApp />;
}

/** 根据管理会话展示登录页或 Bot 管理台。 */
function AuthenticatedApp() {
  const [authenticated, setAuthenticated] = useState(
    Boolean(sessionStorage.getItem('wxbot-admin-token')),
  );
  useEffect(() => {
    const expired = () => setAuthenticated(false);
    window.addEventListener('wxbot-session-expired', expired);
    return () => window.removeEventListener('wxbot-session-expired', expired);
  }, []);
  return authenticated
    ? <Dashboard onLogout={() => setAuthenticated(false)} />
    : <LoginPage onSuccess={() => setAuthenticated(true)} />;
}

function LoginPage({ onSuccess }: { onSuccess: () => void }) {
  const [loading, setLoading] = useState(false);
  const submit = async (values: { username: string; password: string }) => {
    setLoading(true);
    try {
      await api.login(values.username, values.password);
      Message.success('登录成功');
      onSuccess();
    } catch (error) {
      Message.error(messageOf(error));
    } finally {
      setLoading(false);
    }
  };
  return <main className="login-page">
    <section className="login-brand">
      <div className="brand-mark"><IconRobot /></div>
      <Typography.Title heading={2}>wxbot-ilink</Typography.Title>
      <Typography.Paragraph>一个用户，一个稳定 Bot。首次扫码后持久恢复。</Typography.Paragraph>
      <div className="login-feature"><IconCloud /> MySQL 加密会话与可靠消息</div>
      <div className="login-feature"><IconLink /> 多 Bot 运行时完全隔离</div>
      <div className="login-feature"><IconCheckCircle /> 数据库租约保障多副本单活</div>
    </section>
    <Card className="login-card" bordered={false}>
      <Typography.Title heading={4}>登录管理后台</Typography.Title>
      <Typography.Paragraph type="secondary">使用 admin-server 配置的管理员账号密码</Typography.Paragraph>
      <Form layout="vertical" onSubmit={submit} autoComplete="off">
        <FormItem label="账号" field="username" rules={[{ required: true, message: '请输入账号' }]}>
          <Input size="large" placeholder="管理员账号" allowClear />
        </FormItem>
        <FormItem label="密码" field="password" rules={[{ required: true, message: '请输入密码' }]}>
          <Input.Password size="large" placeholder="管理员密码" />
        </FormItem>
        <Button long size="large" type="primary" htmlType="submit" loading={loading}>登录</Button>
      </Form>
    </Card>
  </main>;
}

function Dashboard({ onLogout }: { onLogout: () => void }) {
  const [bots, setBots] = useState<BotRuntimeView[]>([]);
  const [loading, setLoading] = useState(true);
  const [bindVisible, setBindVisible] = useState(false);
  const [qr, setQr] = useState<{ userId: string; value: LoginResponse }>();
  const [sendUser, setSendUser] = useState<string>();
  const load = useCallback(async () => {
    setLoading(true);
    try { setBots(await api.listBots()); }
    catch (error) { Message.error(messageOf(error)); }
    finally { setLoading(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);
  useEffect(() => {
    const timer = window.setInterval(() => { void load(); }, 15000);
    return () => window.clearInterval(timer);
  }, [load]);

  const stats = useMemo(() => ({
    total: bots.length,
    online: bots.filter((bot) => bot.registration.status === 'ONLINE').length,
    attention: bots.filter((bot) => ['LOGIN_REQUIRED', 'ERROR'].includes(bot.registration.status)).length,
  }), [bots]);

  const operate = async (action: () => Promise<unknown>, success: string) => {
    try { await action(); Message.success(success); await load(); }
    catch (error) { Message.error(messageOf(error)); }
  };

  const openLoginQr = useCallback(async (userId: string) => {
    const value = await api.loginBot(userId);
    setQr({ userId, value });
    await load();
  }, [load]);

  const columns = [
    {
      title: '用户 / Bot', dataIndex: 'registration', width: 260,
      render: (_: unknown, row: BotRuntimeView) => <Space>
        <Avatar className="bot-avatar"><IconRobot /></Avatar>
        <div><b>{row.registration.displayName}</b><div className="muted">{row.registration.userId}</div></div>
      </Space>,
    },
    {
      title: '状态', width: 130,
      render: (_: unknown, row: BotRuntimeView) => {
        const meta = statusMeta[row.registration.status];
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '运行实例', width: 140,
      render: (_: unknown, row: BotRuntimeView) => row.running
        ? <Tag icon={<IconCheckCircle />} color="green">本实例运行</Tag>
        : <span className="muted">未运行</span>,
    },
    {
      title: '链路健康', width: 160,
      render: (_: unknown, row: BotRuntimeView) => row.health
        ? <div>{row.health.state}<div className="muted">连续失败 {row.health.consecutivePollFailures}</div></div>
        : <span className="muted">暂无数据</span>,
    },
    {
      title: '更新时间', width: 190,
      render: (_: unknown, row: BotRuntimeView) => new Date(row.registration.updatedAt).toLocaleString(),
    },
    {
      title: '操作', width: 390, fixed: 'right' as const,
      render: (_: unknown, row: BotRuntimeView) => <Space wrap>
        {['LOGIN_REQUIRED', 'ERROR'].includes(row.registration.status) &&
          <Button size="small" type="primary" icon={<IconMobile />} onClick={async () => {
            try { await openLoginQr(row.registration.userId); }
            catch (error) { Message.error(messageOf(error)); }
          }}>扫码</Button>}
        {!row.running && row.registration.status !== 'LOGIN_REQUIRED' &&
          <Button size="small" icon={<IconRefresh />} onClick={() => operate(
            () => api.restoreBot(row.registration.userId), '已发起会话恢复')}>恢复</Button>}
        {row.registration.status === 'ONLINE' && row.running &&
          <Button size="small" icon={<IconSend />}
            onClick={() => setSendUser(row.registration.userId)}>测试消息</Button>}
        {row.running && <Button size="small" icon={<IconPause />} onClick={() => operate(
          () => api.stopBot(row.registration.userId), 'Bot 已安全停止')}>停止</Button>}
        <Button size="small" status="danger" icon={<IconDelete />} onClick={() => confirmUnbind(row, operate)}>解绑</Button>
      </Space>,
    },
  ];

  return <Layout className="app-shell">
    <Sider className="sidebar" width={228}>
      <div className="logo"><IconRobot /><span>wxbot-ilink</span></div>
      <Menu selectedKeys={['bots']} theme="dark">
        <Menu.Item key="bots"><IconApps />Bot 管理</Menu.Item>
      </Menu>
      <div className="sidebar-footer">Apache-2.0 · Java 17</div>
    </Sider>
    <Layout>
      <Header className="topbar">
        <span className="topbar-title">多 Bot 管理后台</span>
        <Button type="text" icon={<IconExport />} onClick={async () => {
          try { await api.logout(); } finally { sessionStorage.removeItem('wxbot-admin-token'); onLogout(); }
        }}>退出登录</Button>
      </Header>
      <Content className="content">
        <PageHeader title="Bot 管理" subTitle="业务 userId 永久绑定，微信 botId 仅属于加密会话">
          <Button icon={<IconRefresh />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" icon={<IconPlus />} onClick={() => setBindVisible(true)}>绑定用户</Button>
        </PageHeader>
        <Row gutter={16} className="stats-row">
          <Col span={8}><Card bordered={false}><Statistic title="绑定总数" value={stats.total} suffix="个" /></Card></Col>
          <Col span={8}><Card bordered={false}><Statistic title="在线 Bot" value={stats.online} suffix="个" styleValue={{ color: '#00b42a' }} /></Card></Col>
          <Col span={8}><Card bordered={false}><Statistic title="需要处理" value={stats.attention} suffix="个" styleValue={{ color: '#ff7d00' }} /></Card></Col>
        </Row>
        <Card bordered={false} className="table-card">
          <Table rowKey={(row) => row.registration.userId} columns={columns} data={bots}
            loading={loading} scroll={{ x: 1320 }} pagination={{ pageSize: 10 }} />
        </Card>
      </Content>
    </Layout>
    <BindModal visible={bindVisible} onCancel={() => setBindVisible(false)} onDone={async () => { setBindVisible(false); await load(); }} />
    <QrModal value={qr} onCancel={() => setQr(undefined)} onBound={load}
      onRetry={openLoginQr} />
    <SendModal userId={sendUser} onCancel={() => setSendUser(undefined)} />
  </Layout>;
}

function BindModal({ visible, onCancel, onDone }: { visible: boolean; onCancel: () => void; onDone: () => Promise<void> }) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  return <Modal title="绑定业务用户" visible={visible} confirmLoading={loading} onCancel={onCancel} onOk={async () => {
    try {
      const values = await form.validate() as { userId: string; displayName: string };
      setLoading(true); await api.bindBot(values.userId, values.displayName);
      Message.success('绑定成功，请完成首次扫码'); form.resetFields(); await onDone();
    } catch (error) { if (error instanceof Error) Message.error(error.message); }
    finally { setLoading(false); }
  }}>
    <Form form={form} layout="vertical">
      <FormItem field="userId" label="业务 userId" rules={[{ required: true, message: '请输入业务 userId' }]}>
        <Input placeholder="例如：customer-10001" />
      </FormItem>
      <FormItem field="displayName" label="Bot 名称" rules={[{ required: true, message: '请输入展示名称' }]}>
        <Input placeholder="例如：客服小助手" />
      </FormItem>
    </Form>
  </Modal>;
}

interface QrModalProps {
  value?: { userId: string; value: LoginResponse };
  onCancel: () => void;
  onBound: () => Promise<void>;
  onRetry: (userId: string) => Promise<void>;
}

/**
 * 展示二维码登录进度，并且只轮询当前 attemptId。
 *
 * <p>递归定时器保证上一请求结束后才会创建下一请求；请求序号和 AbortController 共同阻止关闭弹窗、
 * 切换二维码后到达的旧响应覆盖新状态。
 */
function QrModal({ value, onCancel, onBound, onRetry }: QrModalProps) {
  const [status, setStatus] = useState<LoginStatusResponse>();
  const [syncWarning, setSyncWarning] = useState<string>();
  const [retryLoading, setRetryLoading] = useState(false);
  const completedAttempt = useRef<string>();

  useEffect(() => {
    if (!value) {
      setStatus(undefined);
      setSyncWarning(undefined);
      return undefined;
    }

    const { userId } = value;
    const { attemptId, expiresAt, phase } = value.value;
    let disposed = false;
    let timer: number | undefined;
    let controller: AbortController | undefined;
    let latestRequest = 0;
    let consecutiveFailures = 0;

    setStatus({
      attemptId,
      phase,
      message: null,
      expiresAt,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      version: 0,
      registrationStatus: phase === 'BOUND' ? 'ONLINE' : 'LOGIN_PENDING',
      wechatUserId: isQrLayoutPreview() && phase === 'BOUND'
        ? 'preview-wechat-user-identifier@im.wechat' : null,
      botId: isQrLayoutPreview() && phase === 'BOUND' ? 'preview-bot-identifier@im.bot' : null,
    });
    setSyncWarning(undefined);
    if (isQrLayoutPreview()) return undefined;

    /** 根据页面可见性降低后台标签页的轮询频率。 */
    const schedule = (delay?: number) => {
      if (disposed) return;
      const normalDelay = document.hidden ? 5000 : 1500;
      timer = window.setTimeout(poll, delay ?? normalDelay);
    };

    const poll = async () => {
      if (disposed) return;
      const requestSequence = ++latestRequest;
      controller = new AbortController();
      try {
        const next = await api.getLoginStatus(userId, attemptId, controller.signal);
        if (disposed || requestSequence !== latestRequest || next.attemptId !== attemptId) return;
        consecutiveFailures = 0;
        setSyncWarning(undefined);
        setStatus(next);

        if (isBound(next)) {
          if (completedAttempt.current !== attemptId) {
            completedAttempt.current = attemptId;
            Message.success('微信确认成功，Bot 已完成绑定');
            await onBound();
          }
          return;
        }
        if (isTerminalFailure(next.phase)) return;
        schedule();
      } catch (error) {
        if (disposed || requestSequence !== latestRequest || isAbortError(error)) return;
        consecutiveFailures += 1;
        setSyncWarning(`暂时无法同步扫码状态，正在自动重试：${messageOf(error)}`);
        schedule(Math.min(8000, 1500 * consecutiveFailures));
      }
    };

    schedule(300);
    return () => {
      disposed = true;
      latestRequest += 1;
      if (timer !== undefined) window.clearTimeout(timer);
      controller?.abort();
    };
  }, [value, onBound]);

  const current = status?.attemptId === value?.value.attemptId ? status : undefined;
  const phase = current?.phase ?? value?.value.phase ?? 'WAITING_SCAN';
  const meta = loginPhaseMeta(phase, current);

  return <Modal className="qr-modal" title="微信扫码绑定" visible={Boolean(value)} footer={null}
    style={{ width: 640, maxWidth: 'calc(100vw - 32px)' }} onCancel={onCancel} unmountOnExit>
    {value && <div className={`qr-panel ${isBound(current) ? 'qr-panel-bound' : ''}`}>
      <Steps className="qr-steps" current={meta.step} status={meta.stepStatus}
        labelPlacement="vertical" size="small">
        <Step title="等待扫码" />
        <Step title="已扫码" />
        <Step title="微信确认" />
        <Step title="绑定成功" />
      </Steps>

      <div className={`qr-code-wrap ${meta.qrActive ? '' : 'qr-code-inactive'}`}>
        <QRCodeSVG value={value.value.imageContent} size={220} level="M" />
        {!meta.qrActive && <div className="qr-code-overlay">
          <IconCheckCircle />
          <span>{meta.overlay}</span>
        </div>}
      </div>

      <Typography.Title heading={6}>{meta.title}</Typography.Title>
      <Typography.Paragraph type="secondary">{current?.message || meta.description}</Typography.Paragraph>
      {syncWarning && <Alert className="qr-alert" type="warning" showIcon content={syncWarning} />}

      <Descriptions className="qr-details" column={1} size="small" data={[
        { label: '业务用户', value: value.userId },
        { label: '二维码有效期', value: formatDate(value.value.expiresAt) },
        ...(current?.updatedAt ? [{ label: '状态更新时间', value: formatDate(current.updatedAt) }] : []),
        ...(isBound(current) ? [
          { label: '微信 userId', value: current?.wechatUserId || '未返回' },
          { label: '微信 botId', value: current?.botId || '未返回' },
        ] : []),
      ]} />

      <div className="qr-actions">
        {isTerminalFailure(phase) && <Button type="primary" icon={<IconRefresh />}
          loading={retryLoading} onClick={async () => {
            setRetryLoading(true);
            try { await onRetry(value.userId); }
            catch (error) { Message.error(messageOf(error)); }
            finally { setRetryLoading(false); }
          }}>重新生成二维码</Button>}
        {isBound(current) && <Button type="primary" onClick={onCancel}>完成</Button>}
        {!isBound(current) && !isTerminalFailure(phase) &&
          <Typography.Text type="secondary">状态会自动更新，无需手动确认</Typography.Text>}
      </div>
    </div>}
  </Modal>;
}

function isTerminalFailure(phase: BotLoginPhase) {
  return phase === 'EXPIRED' || phase === 'FAILED';
}

/** 仅在开发环境提供无后端副作用的扫码弹窗视觉检查入口。 */
function isQrLayoutPreview() {
  return import.meta.env.DEV && new URLSearchParams(window.location.search).has('qr-layout-preview');
}

/** CONFIRMED 只有在注册表已在线后才算真正绑定完成，避免快照尚未落库就提前报成功。 */
function isBound(status?: LoginStatusResponse) {
  return Boolean(status && (status.phase === 'BOUND'
    || (status.phase === 'CONFIRMED' && status.registrationStatus === 'ONLINE')));
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError';
}

function formatDate(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '未知' : date.toLocaleString();
}

function loginPhaseMeta(phase: BotLoginPhase, status?: LoginStatusResponse) {
  if (isBound(status)) return {
    step: 4, stepStatus: 'finish' as const, qrActive: false, overlay: '绑定成功',
    title: 'Bot 已完成绑定', description: '微信身份和加密会话已经安全保存，后续恢复无需再次扫码。',
  };
  if (phase === 'EXPIRED') return {
    step: 1, stepStatus: 'error' as const, qrActive: false, overlay: '二维码已过期',
    title: '二维码已过期', description: '请重新生成二维码后再使用微信扫描。',
  };
  if (phase === 'FAILED') return {
    step: 1, stepStatus: 'error' as const, qrActive: false, overlay: '绑定失败',
    title: '绑定没有完成', description: '登录过程出现异常，请重新生成二维码后重试。',
  };
  if (phase === 'CONFIRMED' || phase === 'BINDING') return {
    step: 3, stepStatus: 'process' as const, qrActive: false, overlay: '正在绑定',
    title: '微信已确认，正在完成绑定', description: '后台正在加密保存微信身份和会话，请稍候。',
  };
  if (phase === 'SCANNED') return {
    step: 2, stepStatus: 'process' as const, qrActive: false, overlay: '已扫码',
    title: '已感知扫码', description: '请在手机微信上确认登录。',
  };
  return {
    step: 1, stepStatus: 'process' as const, qrActive: true, overlay: '',
    title: '请使用微信扫描二维码', description: '扫码后，本页面会自动感知并更新绑定进度。',
  };
}

function SendModal({ userId, onCancel }: { userId?: string; onCancel: () => void }) {
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  return <Modal title="发送测试消息" visible={Boolean(userId)} confirmLoading={loading} onCancel={onCancel} onOk={async () => {
    try {
      const values = await form.validate() as { text: string };
      setLoading(true); await api.sendTestMessage(userId!, values.text);
      Message.success('测试消息已发送至当前绑定的微信用户'); form.resetFields(); onCancel();
    } catch (error) { if (error instanceof Error) Message.error(error.message); }
    finally { setLoading(false); }
  }}>
    <Form form={form} layout="vertical">
      <Alert type="info" showIcon content="消息将发送给该业务用户当前绑定的微信账号，无需选择接收人。" />
      <FormItem field="text" label="测试文本" rules={[{ required: true, message: '请输入测试文本' }]}>
        <Input.TextArea placeholder="请输入要发送的测试内容" maxLength={2000} showWordLimit />
      </FormItem>
    </Form>
  </Modal>;
}

function confirmUnbind(row: BotRuntimeView, operate: (action: () => Promise<unknown>, success: string) => Promise<void>) {
  Modal.confirm({
    title: '确认解绑？',
    content: `将清除“${row.registration.displayName}”的加密会话、消息和租约，下次需要重新扫码。`,
    okButtonProps: { status: 'danger' },
    onOk: () => operate(() => api.unbindBot(row.registration.userId), '已解除绑定'),
  });
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '操作失败';
}
