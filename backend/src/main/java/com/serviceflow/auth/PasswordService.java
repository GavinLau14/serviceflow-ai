package com.serviceflow.auth;

import com.serviceflow.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PasswordService {
  private final UserMapper users;
  private final PasswordEncoder encoder;

  public PasswordService(UserMapper users,PasswordEncoder encoder) {
    this.users=users;this.encoder=encoder;
  }

  @Transactional
  public void change(User caller,String current,String next) {
    if(current.getBytes(StandardCharsets.UTF_8).length>72 || next.getBytes(StandardCharsets.UTF_8).length>72)
      throw ApiException.bad("密码不可超过72个UTF-8字节");
    if(next.isBlank() || next.codePointCount(0,next.length())<12)
      throw ApiException.bad("新密码至少需要12个字符");
    User locked=users.lockActive(caller.id);
    if(locked==null || caller.credentialVersion!=locked.credentialVersion)
      throw new ApiException(401,"UNAUTHORIZED","会话已失效，请重新登录");
    if(!encoder.matches(current,locked.passwordHash))throw ApiException.bad("当前密码不正确");
    if(current.equals(next))throw ApiException.bad("新密码不能与当前密码相同");
    // The hash and epoch commit atomically in MySQL. Redis sessions are never
    // revoked before commit, so a rollback cannot lock out an unchanged account.
    if(users.changeCredential(locked.id,locked.credentialVersion,encoder.encode(next))!=1)
      throw new ApiException(409,"CREDENTIAL_CHANGED","账号凭据已变化，请重新登录");
  }
}
