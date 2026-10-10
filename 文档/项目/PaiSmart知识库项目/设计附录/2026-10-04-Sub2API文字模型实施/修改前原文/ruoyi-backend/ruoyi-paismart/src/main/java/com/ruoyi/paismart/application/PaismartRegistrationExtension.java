package com.ruoyi.paismart.application;
import java.util.function.BooleanSupplier;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.RegisterBody;
import com.ruoyi.framework.web.service.RegistrationExtension;
import com.ruoyi.paismart.configuration.PaismartEnabled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
/** 平台注册与邀请消费共事务；注册开关、密码、验证码仍由平台裁决。 */
@Component
@PaismartEnabled
public class PaismartRegistrationExtension implements RegistrationExtension {
    private final InvitationService invites;private final TransactionTemplate tx;
    public PaismartRegistrationExtension(InvitationService invites,@Qualifier("paismartTransactionTemplate")TransactionTemplate tx){this.invites=invites;this.tx=tx;}
    @Override public boolean supports(RegisterBody b){return b.getInvitationCode()!=null && !b.getInvitationCode().isBlank();}
    @Override public boolean register(SysUser user,RegisterBody b,BooleanSupplier insert){return Boolean.TRUE.equals(tx.execute(status->{invites.validate(b.getInvitationCode());if(!insert.getAsBoolean()){status.setRollbackOnly();return false;}if(user.getUserId()==null)throw new IllegalStateException("平台注册未返回稳定用户标识");invites.redeem(user.getUserId(),b.getInvitationCode());return true;}));}
}
