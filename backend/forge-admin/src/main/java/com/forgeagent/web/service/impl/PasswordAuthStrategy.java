package com.forgeagent.web.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.forgeagent.common.core.constant.Constants;
import com.forgeagent.common.core.constant.GlobalConstants;
import com.forgeagent.common.core.constant.SystemConstants;
import com.forgeagent.common.core.enums.LoginType;
import com.forgeagent.common.core.exception.user.CaptchaException;
import com.forgeagent.common.core.exception.user.CaptchaExpireException;
import com.forgeagent.common.core.exception.user.UserException;
import com.forgeagent.common.core.utils.MessageUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.core.utils.ValidatorUtils;
import com.forgeagent.common.json.utils.JsonUtils;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.config.properties.CaptchaProperties;
import com.forgeagent.system.api.model.LoginUser;
import com.forgeagent.system.api.model.PasswordLoginBody;
import com.forgeagent.system.domain.SysUser;
import com.forgeagent.system.domain.vo.SysClientVo;
import com.forgeagent.system.domain.vo.SysUserVo;
import com.forgeagent.system.mapper.SysUserMapper;
import com.forgeagent.web.domain.vo.LoginVo;
import com.forgeagent.web.service.IAuthStrategy;
import com.forgeagent.web.service.SysLoginService;
import org.springframework.stereotype.Service;

/**
 * 密码认证策略
 *
 * @author Michelle.Chung; 改造: 智造 ForgeAgent 2026-11
 */
@Slf4j
@Service("password" + IAuthStrategy.BASE_NAME)
@RequiredArgsConstructor
public class PasswordAuthStrategy implements IAuthStrategy {

    private final CaptchaProperties captchaProperties;
    private final SysLoginService loginService;
    private final SysUserMapper userMapper;

    /**
     * 执行账号密码登录，并按客户端配置生成访问令牌。
     *
     * @param body   登录请求体
     * @param client 当前客户端配置
     * @return 登录结果
     */
    @Override
    public LoginVo login(String body, SysClientVo client) {
        PasswordLoginBody loginBody = JsonUtils.parseObject(body, PasswordLoginBody.class);
        ValidatorUtils.validate(loginBody);
        String username = loginBody.getUsername();
        String password = loginBody.getPassword();
        String code = loginBody.getCode();
        String uuid = loginBody.getUuid();

        boolean captchaEnabled = captchaProperties.getEnable();
        // 验证码开关
        if (captchaEnabled) {
            validateCaptcha(username, code, uuid);
        }
        SysUserVo user = loadUserByUsername(username);
        loginService.checkLogin(LoginType.PASSWORD, username, () -> !BCrypt.checkpw(password, user.getPassword()));
        // 此处可根据登录用户的数据不同 自行创建 loginUser
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
     * 校验图形验证码是否有效且匹配。
     *
     * @param username 用户名
     * @param code     用户输入的验证码
     * @param uuid     验证码缓存标识
     */
    private void validateCaptcha(String username, String code, String uuid) {
        String verifyKey = GlobalConstants.CAPTCHA_CODE_KEY + StringUtils.blankToDefault(uuid, "");
        String captcha = RedisUtils.getCacheObject(verifyKey);
        RedisUtils.deleteObject(verifyKey);
        if (captcha == null) {
            loginService.recordLoginInfo(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        if (!StringUtils.equalsIgnoreCase(code, captcha)) {
            loginService.recordLoginInfo(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"));
            throw new CaptchaException();
        }
    }

    /**
     * 按用户名加载可登录用户，并校验是否存在或被停用。
     *
     * @param username 用户名
     * @return 用户信息
     */
    private SysUserVo loadUserByUsername(String username) {
        SysUserVo user = userMapper.lambda()
            .eq(SysUser::getUserName, username)
            .voOne();
        if (ObjectUtil.isNull(user)) {
            log.info("登录用户：{} 不存在.", username);
            throw new UserException("user.not.exists", username);
        } else if (SystemConstants.DISABLE.equals(user.getStatus())) {
            log.info("登录用户：{} 已被停用.", username);
            throw new UserException("user.blocked", username);
        }
        return user;
    }

}
