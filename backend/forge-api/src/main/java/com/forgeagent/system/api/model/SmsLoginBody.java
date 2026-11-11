package com.forgeagent.system.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.forgeagent.common.core.constant.RegexConstants;
import com.forgeagent.common.core.domain.model.LoginBody;

/**
 * 短信验证码登录请求对象。
 *
 * @author Lion Li; 改造: 智造 ForgeAgent 2026-11
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SmsLoginBody extends LoginBody {

    /**
     * 手机号
     */
    @NotBlank(message = "{user.phonenumber.not.blank}")
    @Pattern(regexp = RegexConstants.MOBILE, message = "手机号格式不正确")
    private String phoneNumber;

    /**
     * 短信code
     */
    @NotBlank(message = "{sms.code.not.blank}")
    private String smsCode;

}
