package com.forgeagent.web.service;

import cn.hutool.crypto.digest.BCrypt;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.forgeagent.common.core.constant.Constants;
import com.forgeagent.common.core.constant.GlobalConstants;
import com.forgeagent.common.core.enums.UserType;
import com.forgeagent.common.core.exception.user.CaptchaException;
import com.forgeagent.common.core.exception.user.CaptchaExpireException;
import com.forgeagent.common.core.exception.user.UserException;
import com.forgeagent.common.core.utils.MessageUtils;
import com.forgeagent.common.core.utils.ServletUtils;
import com.forgeagent.common.core.utils.SpringUtils;
import com.forgeagent.common.core.utils.StringUtils;
import com.forgeagent.common.log.event.LoginInfoEvent;
import com.forgeagent.common.redis.utils.RedisUtils;
import com.forgeagent.common.satoken.utils.LoginHelper;
import com.forgeagent.common.web.config.properties.CaptchaProperties;
import com.forgeagent.system.api.model.RegisterBody;
import com.forgeagent.system.domain.SysUser;
import com.forgeagent.system.domain.bo.SysUserBo;
import com.forgeagent.system.mapper.SysUserMapper;
import com.forgeagent.system.service.ISysUserService;
import org.springframework.stereotype.Service;

/**
 * 注册校验方法
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@RequiredArgsConstructor
@Service
public class SysRegisterService {

    private final ISysUserService userService;
    private final SysUserMapper userMapper;
    private final CaptchaProperties captchaProperties;

    /**
     * 注册
     *
     * @param registerBody 注册请求参数
     */
    public void register(RegisterBody registerBody) {
        String username = registerBody.getUsername();
        String password = registerBody.getPassword();
        // 校验用户类型是否存在
        String userType = UserType.getUserType(registerBody.getUserType()).getUserType();

        boolean captchaEnabled = captchaProperties.getEnable();
        // 验证码开关
        if (captchaEnabled) {
            validateCaptcha(username, registerBody.getCode(), registerBody.getUuid());
        }
        SysUserBo sysUser = new SysUserBo();
        sysUser.setUserName(username);
        sysUser.setNickName(username);
        sysUser.setPassword(BCrypt.hashpw(password));
        sysUser.setUserType(userType);

        boolean exist = userMapper.lambda()
            .eq(SysUser::getUserName, sysUser.getUserName())
            .exists();
        if (exist) {
            throw new UserException("user.register.save.error", username);
        }
        boolean regFlag = userService.registerUser(sysUser);
        if (!regFlag) {
            throw new UserException("user.register.error");
        }
        recordLoginInfo(username, Constants.REGISTER, MessageUtils.message("user.register.success"));
    }

    /**
     * 校验验证码
     *
     * @param username 用户名
     * @param code     验证码
     * @param uuid     唯一标识
     */
    public void validateCaptcha(String username, String code, String uuid) {
        String verifyKey = GlobalConstants.CAPTCHA_CODE_KEY + StringUtils.blankToDefault(uuid, "");
        String captcha = RedisUtils.getCacheObject(verifyKey);
        RedisUtils.deleteObject(verifyKey);
        if (captcha == null) {
            recordLoginInfo(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.expire"));
            throw new CaptchaExpireException();
        }
        if (!StringUtils.equalsIgnoreCase(code, captcha)) {
            recordLoginInfo(username, Constants.LOGIN_FAIL, MessageUtils.message("user.jcaptcha.error"));
            throw new CaptchaException();
        }
    }

    /**
     * 记录登录信息
     *
     * @param username 用户名
     * @param status   状态
     * @param message  消息内容
     */
    private void recordLoginInfo(String username, String status, String message) {
        LoginInfoEvent loginInfoEvent = new LoginInfoEvent();
        loginInfoEvent.setUsername(username);
        loginInfoEvent.setStatus(status);
        loginInfoEvent.setMessage(message);
        HttpServletRequest request = ServletUtils.getRequest();
        if (request != null) {
            loginInfoEvent.setIp(ServletUtils.getClientIP(request));
            loginInfoEvent.setUserAgent(request.getHeader("User-Agent"));
            loginInfoEvent.setClientId(request.getHeader(LoginHelper.CLIENT_KEY));
        }
        SpringUtils.context().publishEvent(loginInfoEvent);
    }

}
