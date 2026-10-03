import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Form, Input, message } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import http from '@/utils/http';
import { getCaptchaImage, type CaptchaImage } from '@/services/platform-auth';
import { getPostLoginRedirect } from '@/utils/auth';
import './style.scss';

const LoginPage = () => {
  const [loading, setLoading] = useState(false);
  const [captcha, setCaptcha] = useState<CaptchaImage | null>(null);
  const [captchaLoading, setCaptchaLoading] = useState(false);
  const [form] = Form.useForm();
  const navigate = useNavigate();

  const reloadCaptcha = async () => {
    setCaptchaLoading(true);
    setCaptcha(null);
    form.setFieldValue('code', '');
    try {
      const next = await getCaptchaImage();
      if (next.captchaEnabled !== false && (!next.img || !next.uuid)) {
        throw new Error('验证码响应不完整');
      }
      setCaptcha(next);
    } catch (error: any) {
      message.error(error?.message || error?.msg || '验证码获取失败，请重试');
    } finally {
      setCaptchaLoading(false);
    }
  };

  useEffect(() => { void reloadCaptcha(); }, []);

  const onFinish = async (values: { username: string; password: string; code?: string }) => {
    if (!captcha || captchaLoading) return;
    setLoading(true);
    try {
      const response = await http.post('/api/login', {
        username: values.username,
        password: values.password,
        code: values.code,
        uuid: captcha.uuid,
      });

      if (response.accessToken) {
        localStorage.setItem('accessToken', response.accessToken);
        localStorage.removeItem('refreshToken');
        message.success('登录成功！');
        navigate(getPostLoginRedirect(), { replace: true });
      } else {
        message.error('登录失败，请重试');
      }
    } catch (error: any) {
      message.error(error?.message || error?.msg || '登录失败');
      await reloadCaptcha();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-container">
        <div className="login-header">
          <div className="login-brand">
            <img src="/paiflow-icon.svg" alt="PaiFlow logo" className="login-brand-logo" />
            <h1>PaiFlow</h1>
          </div>
          <p>使用若依平台账号登录</p>
        </div>
        <Form
          name="login"
          form={form}
          className="login-form"
          onFinish={onFinish}
          autoComplete="off"
        >
          <Form.Item
            name="username"
            rules={[{ required: true, message: '请输入用户名！' }]}
          >
            <Input
              prefix={<UserOutlined />}
              placeholder="用户名"
              size="large"
            />
          </Form.Item>

          <Form.Item
            name="password"
            rules={[{ required: true, message: '请输入密码！' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="密码"
              size="large"
            />
          </Form.Item>

          {captcha && captcha.captchaEnabled !== false && (
            <Form.Item name="code" rules={[{ required: true, message: '请输入验证码！' }]}>
              <Input size="large" placeholder="验证码" suffix={
                <button type="button" onClick={() => void reloadCaptcha()} aria-label="刷新验证码">
                  <img src={`data:image/gif;base64,${captcha.img}`} alt="验证码，点击刷新" />
                </button>
              } />
            </Form.Item>
          )}
          {!captcha && (
            <Form.Item>
              <Button onClick={() => void reloadCaptcha()} loading={captchaLoading}>重新获取验证码</Button>
            </Form.Item>
          )}
          <Form.Item>
            <Button
              type="primary"
              htmlType="submit"
              className="login-button"
              size="large"
              loading={loading}
              disabled={!captcha || captchaLoading}
              block
            >
              登录
            </Button>
          </Form.Item>
        </Form>
      </div>
    </div>
  );
};

export default LoginPage;
