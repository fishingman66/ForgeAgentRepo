package com.forgeagent.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.constant.Constants;
import com.forgeagent.common.core.constant.GlobalConstants;
import com.forgeagent.common.core.constant.SystemConstants;
import com.forgeagent.common.core.enums.LoginType;
import com.forgeagent.common.core.exception.user.CaptchaExpireException;
import com.forgeagent.common.core.exception.user.UserException;
import com.forgeagent.common.core.utils.MessageUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.core.utils.ValidatorUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.system.api.model.LoginUser;
import com.forgeagent.system.api.model.SmsLoginBody;
import com.forgeagent.system.domain.SysUser;
import com.forgeagent.system.domain.vo.SysClientVo;
import com.forgeagent.system.domain.vo.SysUserVo;
import com.forgeagent.system.mapper.SysUserMapper;
import com.forgeagent.web.domain.vo.LoginVo;
import com.forgeagent.web.service.IAuthStrategy;
import com.forgeagent.web.service.SysLoginService;
import org.springframework.stereotype.Service;

/**
 * 短信认证策略
 *
 * @author Michelle.Chung; 改造: 智造 ForgeAgent 2026-11
 */
@Slf4j
@Service("sms" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class SmsAuthStrategy implements IAuthStrategy {

    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    /**
     * 执行短信验证码登录，并按客户端配置生成访问令牌。
     *
     * @param body   登录请求体
     * @param client 当前客户端配置
     * @return 登录结果
     */
    @Override
    public LoginVo login(String body, SysClientVo client) {
        SmsLoginBody loginBody = JsonUtils.parseObject(body, SmsLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String phoneNumber = loginBody.getPhoneNumber();
        String smsCode = loginBody.getSmsCode();
        SysUserVo user = loadUserByPhoneNumber(phoneNumber);
        loginService.checkLogin(LoginType.SMS, user.getUserName(), () -> !validateSmsCode(phoneNumber, smsCode));
        // 此处可根据登录用户的数据不同 自行创建 loginUser 属性不够用继承扩展就行了
        LoginUser loginUser = loginService.buildLoginUser(user);
        loginUser.setClientKey(client.getClientKey());
        loginUser.setDeviceType(client.getDeviceType());
        SaLoginParameter model = IAuthStrategy.buildLoginParameter(client);
        // 生成token
        LoginHelper.login(loginUser, model);

        LoginVo loginVo = new LoginVo();
        loginVo.setAccessToken(StpUtil.getTokenValue());
        loginVo.setExpireIn(StpUtil.getTokenTimeout());
        loginVo.setClientId(client.getClientId());
        return loginVo;
    }

    /**
     * 校验短信验证码是否存在且匹配。
     *
     * @param phoneNumber 手机号
     * @param smsCode     用户输入的短信验证码
     * @return 是否校验通过
     */
    private boolean validateSmsCode(String phoneNumber, String smsCode) {
        String code = RedisUtils.getCacheObject(GlobalConstants.CAPTCHA_CODE_KEY + phoneNumber);
        if (StringUtils.isBlank(code)) {
            loginService.recordLoginInfo(phoneNumber, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        return code.equals(smsCode);
    }

    /**
     * 按手机号加载可登录用户，并校验是否存在或被停用。
     *
     * @param phoneNumber 手机号
     * @return 用户信息
     */
    private SysUserVo loadUserByPhoneNumber(String phoneNumber) {
        SysUserVo user = userMapper.lambda()
            .eq(SysUser::getPhoneNumber, phoneNumber)
            .voOne();
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", phoneNumber);
            throw new UserException("user.not.exists", phoneNumber);
        } else if (SystemConstants.DISABLE.equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", phoneNumber);
            throw new UserException("user.blocked", phoneNumber);
        }
        return user;
    }

}
