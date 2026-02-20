package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    /**
     * 向手机发送短信验证码并保存到Session
     *
     * @param phone   手机号
     * @param session 会话
     * @return 是否发送成功
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号是否有效
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("无效的手机号");
        }

        // 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 保存验证码到Session
        session.setAttribute("code", code);

        // 发送验证码 (先忽略具体实现)
        log.debug("验证码发送成功：{}", code);

        return Result.ok();
    }
}
