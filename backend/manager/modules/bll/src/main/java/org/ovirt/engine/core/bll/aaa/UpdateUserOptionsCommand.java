package org.ovirt.engine.core.bll.aaa;

import static org.ovirt.engine.core.common.AuditLogType.USER_FAILED_UPDATE_ADUSER;
import static org.ovirt.engine.core.common.AuditLogType.USER_UPDATE;
import static org.ovirt.engine.core.common.errors.EngineMessage.ACTION_TYPE_FAILED_USER_NOT_EXISTS;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.CommandBase;
import org.ovirt.engine.core.bll.MultiLevelAdministrationHandler;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.UpdateUserParameters;
import org.ovirt.engine.core.common.businessentities.aaa.DbUser;
import org.ovirt.engine.core.dao.DbUserDao;

public class UpdateUserOptionsCommand<T extends UpdateUserParameters> extends CommandBase<T> {

    @Inject
    private DbUserDao dbUserDao;

    public UpdateUserOptionsCommand(T params, CommandContext commandContext) {
        super(params, commandContext);
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        return getSucceeded() ? USER_UPDATE : USER_FAILED_UPDATE_ADUSER;
    }

    @Override
    protected void executeCommand() {
        DbUser userToUpdate = getParameters().getUserToUpdate();
        DbUser current = dbUserDao.getByExternalId(userToUpdate.getDomain(), userToUpdate.getExternalId());
        if (current == null) {
            addValidationMessage(ACTION_TYPE_FAILED_USER_NOT_EXISTS);
            setSucceeded(false);
            return;
        }

        // if user was modified in the meantime then take all immutable fields from newest version
        // pick the value of mutable field (userOptions) from parameters and overwrite existing value
        current.setUserOptions(userToUpdate.getUserOptions());
        dbUserDao.update(current);
        setActionReturnValue(current.getId());
        setSucceeded(true);
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.singletonList(new PermissionSubject(MultiLevelAdministrationHandler.SYSTEM_OBJECT_ID,
                VdcObjectType.System,
                getActionType().getActionGroup()));
    }

}
