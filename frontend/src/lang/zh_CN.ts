export default {
  // 路由国际化
  route: {
    dashboard: '首页',
    document: '项目文档'
  },
  // 帮助中心(B1/F3):客户-facing 文案
  help: {
    title: '帮助中心',
    subtitle: '查找答案,快速自助解决问题',
    searchPlaceholder: '搜索问题、关键词...',
    searchButton: '搜索',
    searching: '搜索中...',
    allArticles: '全部文章',
    latest: '最新更新',
    faq: '常见问题',
    empty: '没有找到相关内容',
    emptyHint: '试试换个关键词,或直接向 AI 提问',
    askAi: '向 AI 提问',
    turnHuman: '转人工',
    wasHelpful: '这篇文章对你有帮助吗?',
    helpful: '有帮助',
    notHelpful: '没帮助',
    feedbackDone: '感谢你的反馈',
    back: '返回列表',
    notFound: '文章不存在或已下线',
    lang: '语言',
    poweredBy: '由 ForgeAgent 提供支持'
  },
  // 登录页面国际化
  login: {
    username: '用户名',
    password: '密码',
    login: '登 录',
    logging: '登 录 中...',
    code: '验证码',
    rememberPassword: '记住我',
    switchRegisterPage: '立即注册',
    rule: {
      username: {
        required: '请输入您的账号'
      },
      password: {
        required: '请输入您的密码'
      },
      code: {
        required: '请输入验证码'
      }
    },
    social: {
      wechat: '微信登录',
      maxkey: 'MaxKey登录',
      topiam: 'TopIam登录',
      gitee: 'Gitee登录',
      github: 'Github登录'
    }
  },
  // 注册页面国际化
  register: {
    username: '用户名',
    password: '密码',
    confirmPassword: '确认密码',
    register: '注 册',
    registering: '注 册 中...',
    registerSuccess: '恭喜你，您的账号 {username} 注册成功！',
    code: '验证码',
    switchLoginPage: '使用已有账户登录',
    rule: {
      username: {
        required: '请输入您的账号',
        length: '用户账号长度必须介于 {min} 和 {max} 之间'
      },
      password: {
        required: '请输入您的密码',
        length: '用户密码长度必须介于 {min} 和 {max} 之间',
        pattern: '不能包含非法字符：{strings}'
      },
      code: {
        required: '请输入验证码'
      },
      confirmPassword: {
        required: '请再次输入您的密码',
        equalToPassword: '两次输入的密码不一致'
      }
    }
  },
  // 导航栏国际化
  navbar: {
    full: '全屏',
    language: '语言',
    dashboard: '首页',
    document: '项目文档',
    message: '消息',
    layoutSize: '布局大小',
    layoutSetting: '布局设置',
    personalCenter: '个人中心',
    logout: '退出登录'
  }
};
