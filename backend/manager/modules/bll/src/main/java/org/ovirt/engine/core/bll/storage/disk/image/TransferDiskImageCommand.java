package org.ovirt.engine.core.bll.storage.disk.image;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Typed;
import javax.inject.Inject;

import org.ovirt.engine.core.bll.CommandActionState;
import org.ovirt.engine.core.bll.LockMessagesMatchUtil;
import org.ovirt.engine.core.bll.NonTransactiveCommandAttribute;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.quota.QuotaConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageConsumptionParameter;
import org.ovirt.engine.core.bll.quota.QuotaStorageDependent;
import org.ovirt.engine.core.bll.tasks.CommandCoordinatorUtil;
import org.ovirt.engine.core.bll.tasks.CommandHelper;
import org.ovirt.engine.core.bll.tasks.interfaces.CommandCallback;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.bll.validator.storage.DiskImagesValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskValidator;
import org.ovirt.engine.core.bll.validator.storage.StorageDomainValidator;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.VdcObjectType;
import org.ovirt.engine.core.common.action.ActionReturnValue;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.AddDiskParameters;
import org.ovirt.engine.core.common.action.LockProperties;
import org.ovirt.engine.core.common.action.RemoveDiskParameters;
import org.ovirt.engine.core.common.action.TransferDiskImageParameters;
import org.ovirt.engine.core.common.action.TransferImageStatusParameters;
import org.ovirt.engine.core.common.businessentities.ActionGroup;
import org.ovirt.engine.core.common.businessentities.StorageDomain;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VmBackup;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.ImageStatus;
import org.ovirt.engine.core.common.businessentities.storage.ImageTicket;
import org.ovirt.engine.core.common.businessentities.storage.ImageTicketInformation;
import org.ovirt.engine.core.common.businessentities.storage.ImageTransfer;
import org.ovirt.engine.core.common.businessentities.storage.ImageTransferBackend;
import org.ovirt.engine.core.common.businessentities.storage.ImageTransferPhase;
import org.ovirt.engine.core.common.businessentities.storage.TransferType;
import org.ovirt.engine.core.common.businessentities.storage.VolumeFormat;
import org.ovirt.engine.core.common.businessentities.storage.VolumeType;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.constants.StorageConstants;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.locks.LockInfo;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.utils.SizeConverter;
import org.ovirt.engine.core.common.vdscommands.AddImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.ExtendImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.GetImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.ImageActionsVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.NbdServerVDSParameters;
import org.ovirt.engine.core.common.vdscommands.PrepareImageVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.RemoveImageTicketVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.SetVolumeLegalityVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.compat.CommandStatus;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dao.DiskDao;
import org.ovirt.engine.core.dao.ImageDao;
import org.ovirt.engine.core.dao.ImageTransferDao;
import org.ovirt.engine.core.dao.StorageDomainDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VmBackupDao;
import org.ovirt.engine.core.dao.VmDao;
import org.ovirt.engine.core.utils.EngineLocalConfig;
import org.ovirt.engine.core.vdsbroker.vdsbroker.PrepareImageReturn;

@NonTransactiveCommandAttribute
public class TransferDiskImageCommand<T extends TransferDiskImageParameters> extends BaseImagesCommand<T> implements QuotaStorageDependent {
    private static final boolean LEGAL_IMAGE = true;
    private static final boolean ILLEGAL_IMAGE = false;
    private static final int PROXY_DATA_PORT = 54323;
    private static final int PROXY_CONTROL_PORT = 54324;
    private static final String HTTP_SCHEME = "http://";
    private static final String HTTPS_SCHEME = "https://";
    private static final String IMAGES_PATH = "/images";
    private static final String FILE_URL_SCHEME = "file://";
    private static final String IMAGE_TYPE = "disk";

    @Inject
    private ImageTransferDao imageTransferDao;
    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private DiskDao diskDao;
    @Inject
    private StorageDomainDao storageDomainDao;
    @Inject
    private VmDao vmDao;
    @Inject
    private ImageTransferUpdater imageTransferUpdater;
    @Inject
    private ImageDao imageDao;
    @Inject
    private VdsDao vdsDao;
    @Inject
    private VmBackupDao vmBackupDao;
    @Inject
    private CommandCoordinatorUtil commandCoordinatorUtil;
    @Inject
    @Typed(TransferImageCommandCallback.class)
    private Instance<TransferImageCommandCallback> callbackProvider;

    private ImageioClient proxyClient;
    private VmBackup vmBackup;

    public TransferDiskImageCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    @Override
    protected void setActionMessageParameters() {
        addValidationMessage(EngineMessage.VAR__TYPE__DISK);
        addValidationMessage(EngineMessage.VAR__ACTION__TRANSFER);
    }

    protected boolean validateCreateImage() {
        ActionReturnValue returnValue = CommandHelper.validate(ActionType.AddDisk, getAddDiskParameters(),
                getContext().clone());
        getReturnValue().setValidationMessages(returnValue.getValidationMessages());
        return returnValue.isValid();
    }

    protected void createImage() {
        runInternalAction(ActionType.AddDisk, getAddDiskParameters(), cloneContextAndDetachFromParent());
    }

    protected String prepareImage(Guid vdsId, Guid imagedTicketId) {
        if (getParameters().getBackupId() != null) {
            return vmBackupDao.getBackupUrlForDisk(
                    getParameters().getBackupId(), getDiskImage().getId());
        }
        VDSReturnValue vdsRetVal = runVdsCommand(VDSCommandType.PrepareImage,
                    getPrepareParameters(vdsId));
        if (getTransferBackend() == ImageTransferBackend.NBD) {
            vdsRetVal = runVdsCommand(VDSCommandType.StartNbdServer,
                    getStartNbdServerParameters(vdsId, imagedTicketId));
            return (String) vdsRetVal.getReturnValue();
        } else {
            return FILE_URL_SCHEME + ((PrepareImageReturn) vdsRetVal.getReturnValue()).getImagePath();
        }
    }

    protected boolean validateImageTransfer() {
        DiskImage diskImage = getDiskImage();
        DiskValidator diskValidator = getDiskValidator(diskImage);
        DiskImagesValidator diskImagesValidator = getDiskImagesValidator(diskImage);
        StorageDomainValidator storageDomainValidator = getStorageDomainValidator(
                storageDomainDao.getForStoragePool(diskImage.getStorageIds().get(0), diskImage.getStoragePoolId()));
        boolean isValid =
                validate(diskValidator.isDiskExists())
                && validate(diskImagesValidator.diskImagesNotIllegal())
                && validate(storageDomainValidator.isDomainExistAndActive());
        if (getParameters().getBackupId() != null) {
            return isValid && validate(isVmBackupExists()) && validate(isFormatApplicableForBackup());
        }
        return isValid
                && validateActiveDiskPluggedToAnyNonDownVm(diskImage, diskValidator)
                && validate(diskImagesValidator.diskImagesNotLocked());
    }

    private boolean validateActiveDiskPluggedToAnyNonDownVm(DiskImage diskImage, DiskValidator diskValidator) {
        return diskImage.isDiskSnapshot() || validate(diskValidator.isDiskPluggedToAnyNonDownVm(false));
    }

    private ValidationResult isVmBackupExists() {
        VmBackup vmBackup = getVmBackup();
        if (vmBackup == null) {
            return new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_VM_BACKUP_NOT_EXIST);
        }
        return ValidationResult.VALID;
    }

    private ValidationResult isFormatApplicableForBackup() {
        if (getParameters().getVolumeFormat() == VolumeFormat.COW) {
            return new ValidationResult(EngineMessage.ACTION_TYPE_FAILED_FORMAT_NOT_APPLICABLE_FOR_BACKUP);
        }
        return ValidationResult.VALID;
    }

    protected DiskImagesValidator getDiskImagesValidator(DiskImage diskImage) {
        return new DiskImagesValidator(diskImage);
    }

    protected DiskValidator getDiskValidator(DiskImage diskImage) {
        return new DiskValidator(diskImage);
    }

    protected StorageDomainValidator getStorageDomainValidator(StorageDomain storageDomain) {
        return new StorageDomainValidator(storageDomain);
    }

    private PrepareImageVDSCommandParameters getPrepareParameters(Guid vdsId) {
        return new PrepareImageVDSCommandParameters(vdsId,
                getStoragePool().getId(),
                getStorageDomainId(),
                getDiskImage().getId(),
                getDiskImage().getImageId(), true);
    }

    private NbdServerVDSParameters getStartNbdServerParameters(Guid vdsId, Guid imagedTicketId) {
        NbdServerVDSParameters nbdServerVDSParameters = new NbdServerVDSParameters(vdsId);
        nbdServerVDSParameters.setServerId(imagedTicketId);
        nbdServerVDSParameters.setStorageDomainId(getParameters().getStorageDomainId());
        nbdServerVDSParameters.setImageId(getDiskImage().getId());
        nbdServerVDSParameters.setVolumeId(getDiskImage().getImageId());
        nbdServerVDSParameters.setReadonly(getParameters().getTransferType() == TransferType.Download);
        nbdServerVDSParameters.setDiscard(isSparseImage());
        return nbdServerVDSParameters;
    }

    protected void tearDownImage(Guid vdsId, Guid backupId) {
        if (backupId != null) {
            // shouldn't teardown as prepare wasn't invoked
            return;
        }

        DiskImage image = getDiskImage();
        if (image.isDiskSnapshot() && !isDiskSnapshotPluggedToDownVmsOnly(image)) {
            // shouldn't teardown snapshot disk that attached to a running VM
            return;
        }

        boolean tearDownFailed = false;

        if (getTransferBackend() == ImageTransferBackend.FILE) {
            if (!Guid.Empty.equals(image.getImageTemplateId())) {
                LockInfo lockInfo =
                        lockManager.getLockInfo(getImage().getImageTemplateId() + LockingGroup.TEMPLATE.toString());

                if (lockInfo != null) {
                    log.info("The template image is being used, skipping teardown");
                    return;
                }
            }

            VDSReturnValue teardownImageVdsRetVal = runVdsCommand(VDSCommandType.TeardownImage,
                    getImageActionsParameters(vdsId));
            if (!teardownImageVdsRetVal.getSucceeded()) {
                log.warn("Failed to tear down image '{}' for image transfer session: {}",
                        image, teardownImageVdsRetVal.getVdsError());
                tearDownFailed = true;
            }
        }

        if (tearDownFailed) {
            // Invoke log method directly rather than relying on infra, because teardown
            // failure may occur during command execution, e.g. if the upload is paused.
            addCustomValue("DiskAlias", image.getDiskAlias());
            auditLogDirector.log(this, AuditLogType.TRANSFER_IMAGE_TEARDOWN_FAILED);
        }
    }

    private boolean stopNbdServer(Guid vdsId, Guid imageTicketId) {
        NbdServerVDSParameters nbdServerVDSParameters = new NbdServerVDSParameters(vdsId);
        nbdServerVDSParameters.setServerId(imageTicketId);
        VDSReturnValue stopNbdServerVdsRetVal = runVdsCommand(VDSCommandType.StopNbdServer,
                nbdServerVDSParameters);
        if (!stopNbdServerVdsRetVal.getSucceeded()) {
            log.warn("Failed to stop NBD server for ticket id '{}': {}",
                    imageTicketId, stopNbdServerVdsRetVal.getVdsError());
            return false;
        }
        return true;
    }

    protected boolean isDiskSnapshotPluggedToDownVmsOnly(DiskImage diskImage) {
        return validate(getDiskValidator(diskImage).isDiskPluggedToAnyNonDownVm(false));
    }

    private AddDiskParameters getAddDiskParameters() {
        AddDiskParameters diskParameters = getParameters().getAddDiskParameters();
        diskParameters.setParentCommand(getActionType());
        diskParameters.setParentParameters(getParameters());
        diskParameters.setShouldRemainIllegalOnFailedExecution(true);
        diskParameters.setSkipDomainCheck(true);
        return diskParameters;
    }

    protected ImageActionsVDSCommandParameters getImageActionsParameters(Guid vdsId) {
        return new ImageActionsVDSCommandParameters(vdsId,
                getStoragePool().getId(),
                getStorageDomainId(),
                getDiskImage().getId(),
                getDiskImage().getImageId());
    }

    protected String getImageAlias() {
        return  getParameters().getAddDiskParameters() != null ?
                getParameters().getAddDiskParameters().getDiskInfo().getDiskAlias() :
                getDiskImage().getDiskAlias();
    }

    protected DiskImage getDiskImage() {
        if (!Guid.isNullOrEmpty(getParameters().getImageId())) {
            setImageId(getParameters().getImageId());
            return super.getDiskImage();
        }
        DiskImage diskImage = super.getDiskImage();
        if (diskImage == null) {
            diskImage = (DiskImage) diskDao.get(getParameters().getImageGroupID());
        }
        return diskImage;
    }

    private VmBackup getVmBackup() {
        if (vmBackup == null) {
            vmBackup = vmBackupDao.get(getParameters().getBackupId());
        }
        return vmBackup;
    }

    @Override
    public List<QuotaConsumptionParameter> getQuotaStorageConsumptionParameters() {
        List<QuotaConsumptionParameter> list = new ArrayList<>();
        if (getParameters().getAddDiskParameters() != null) {
            AddDiskParameters parameters = getAddDiskParameters();
            list.add(new QuotaStorageConsumptionParameter(
                    ((DiskImage) parameters.getDiskInfo()).getQuotaId(),
                    QuotaConsumptionParameter.QuotaAction.CONSUME,
                    getStorageDomainId(),
                    (double) parameters.getDiskInfo().getSize() / SizeConverter.BYTES_IN_GB));
        }

        return list;
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        List<PermissionSubject> listPermissionSubjects = new ArrayList<>();
        if (isImageProvided()) {
            listPermissionSubjects.add(new PermissionSubject(getParameters().getImageGroupID(),
                    VdcObjectType.Disk,
                    ActionGroup.EDIT_DISK_PROPERTIES));
        } else {
            listPermissionSubjects.add(new PermissionSubject(getParameters().getStorageDomainId(),
                    VdcObjectType.Storage,
                    ActionGroup.CREATE_DISK));
        }

        return listPermissionSubjects;
    }

    @Override
    protected Map<String, Pair<String, String>> getSharedLocks() {
        Map<String, Pair<String, String>> locks = new HashMap<>();
        if (getParameters().getBackupId() != null) {
            // StartVmBackup should handle locks
            return locks;
        }
        if (!Guid.isNullOrEmpty(getParameters().getImageId())) {
            List<VM> vms = vmDao.getVmsListForDisk(getDiskImage().getId(), true);
            vms.forEach(vm -> locks.put(vm.getId().toString(),
                    LockMessagesMatchUtil.makeLockingPair(LockingGroup.VM, EngineMessage.ACTION_TYPE_FAILED_VM_IS_LOCKED)));
        }
        return locks;
    }

    @Override
    protected Map<String, Pair<String, String>> getExclusiveLocks() {
        Map<String, Pair<String, String>> locks = new HashMap<>();
        if (getParameters().getBackupId() != null) {
            // StartVmBackup should handle locks
            return locks;
        }
        if (getDiskImage() != null) {
            locks.put(getDiskImage().getId().toString(),
                    LockMessagesMatchUtil.makeLockingPair(LockingGroup.DISK, EngineMessage.ACTION_TYPE_FAILED_DISK_IS_LOCKED));
        }
        return locks;
    }

    @Override
    protected void executeCommand() {
        log.info("Creating ImageTransfer entity for command '{}'", getCommandId());
        ImageTransfer entity = new ImageTransfer(getCommandId());
        entity.setCommandType(getActionType());
        entity.setPhase(ImageTransferPhase.INITIALIZING);
        entity.setType(getParameters().getTransferType());
        entity.setActive(false);
        entity.setLastUpdated(new Date());
        entity.setBytesTotal(isImageProvided() ? getTransferSize() : getParameters().getTransferSize());
        entity.setClientInactivityTimeout(getParameters().getClientInactivityTimeout() != null ?
                getParameters().getClientInactivityTimeout() :
                getTransferImageClientInactivityTimeoutInSeconds());
        entity.setImageFormat(getTransferImageFormat());
        entity.setBackend(getTransferBackend());
        entity.setBackupId(getParameters().getBackupId());
        entity.setTransferClientType(getParameters().getTransferClientType());
        imageTransferDao.save(entity);

        if (isImageProvided()) {
            handleImageIsReadyForTransfer();
        } else {
            if (getParameters().getTransferType() == TransferType.Download) {
                failValidation(EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
                setSucceeded(false);
                return;
            }
            log.info("Creating {} image", IMAGE_TYPE);
            createImage();
        }

        setActionReturnValue(getCommandId());
        setSucceeded(true);
    }

    private boolean isImageProvided() {
        return !Guid.isNullOrEmpty(getParameters().getImageId()) ||
                !Guid.isNullOrEmpty(getParameters().getImageGroupID());
    }

    private VolumeFormat getTransferImageFormat() {
        if (getParameters().getVolumeFormat() != null) {
            return getParameters().getVolumeFormat();
        }
        if (isImageProvided()) {
            return getDiskImage().getVolumeFormat();
        }
        return ((DiskImage) getParameters().getAddDiskParameters().getDiskInfo()).getVolumeFormat();
    }

    protected ImageTransferBackend getTransferBackend() {
        if (getParameters().getBackupId() != null) {
            // Incremental backup uses NBD transfer backend
            return ImageTransferBackend.NBD;
        }
        return getParameters().getVolumeFormat() == VolumeFormat.RAW ?
                ImageTransferBackend.NBD : ImageTransferBackend.FILE;
    }

    public void proceedCommandExecution(Guid childCmdId) {
        ImageTransfer entity = imageTransferDao.get(getCommandId());
        if (entity == null || entity.getPhase() == null) {
            log.error("Image transfer status entity corrupt or missing from database"
                         + " for image transfer command '{}'", getCommandId());
            setCommandStatus(CommandStatus.FAILED);
            return;
        }
        if (entity.getDiskId() != null) {
            // Make the disk id available for all states below.  If the transfer is still
            // initializing, this may be set below in the INITIALIZING block instead.
            setImageGroupId(entity.getDiskId());
        }

        long ts = System.currentTimeMillis() / 1000;
        executeStateHandler(entity, ts, childCmdId);
    }

    public void executeStateHandler(ImageTransfer entity, long timestamp, Guid childCmdId) {
        StateContext context = new StateContext();
        context.entity = entity;
        context.iterationTimestamp = timestamp;
        context.childCmdId = childCmdId;

        // State handler methods are responsible for calling setCommandStatus
        // as well as updating the entity to reflect transitions.
        switch (entity.getPhase()) {
            case INITIALIZING:
                handleInitializing(context);
                break;
            case RESUMING:
                handleResuming(context);
                break;
            case TRANSFERRING:
                handleTransferring(context);
                break;
            case PAUSED_SYSTEM:
                handlePausedSystem(context);
                break;
            case PAUSED_USER:
                handlePausedUser(context);
                break;
            case CANCELLED_USER:
                handleCancelledUser();
                break;
            case CANCELLED_SYSTEM:
                handleCancelledSystem();
                break;
            case FINALIZING_SUCCESS:
                handleFinalizingSuccess(context);
                break;
            case FINALIZING_FAILURE:
                handleFinalizingFailure(context);
                break;
            case FINALIZING_CLEANUP:
                handleFinalizingCleanup(context);
                break;
            case FINISHED_SUCCESS:
                handleFinishedSuccess();
                break;
            case FINISHED_FAILURE:
                handleFinishedFailure();
                break;
            case FINISHED_CLEANUP:
                handleFinishedCleanup();
                break;
            }
    }

    private void handleInitializing(final StateContext context) {
        if (context.childCmdId == null) {
            // Guard against callback invocation before executeCommand() is complete
            return;
        }

        switch (commandCoordinatorUtil.getCommandStatus(context.childCmdId)) {
            case NOT_STARTED:
            case ACTIVE:
                log.info("Waiting for {} to be added for image transfer command '{}'",
                        IMAGE_TYPE, getCommandId());
                return;
            case SUCCEEDED:
                break;
            default:
                log.error("Failed to add {} for image transfer command '{}'",
                        IMAGE_TYPE, getCommandId());
                setCommandStatus(CommandStatus.FAILED);
                return;
        }

        ActionReturnValue addDiskRetVal = commandCoordinatorUtil.getCommandReturnValue(context.childCmdId);
        if (addDiskRetVal == null || !addDiskRetVal.getSucceeded()) {
            log.error("Failed to add {} (command status was success, but return value was failed)"
                    + " for image transfer command '{}'", IMAGE_TYPE, getCommandId());
            setReturnValue(addDiskRetVal);
            setCommandStatus(CommandStatus.FAILED);
            return;
        }

        Guid createdId = addDiskRetVal.getActionReturnValue();
        // Saving disk id in the parameters in order to persist it in command_entities table
        getParameters().setImageGroupID(createdId);
        handleImageIsReadyForTransfer();
    }

    protected void handleImageIsReadyForTransfer() {
        DiskImage image = getDiskImage();
        Guid domainId = image.getStorageIds().get(0);

        getParameters().setStorageDomainId(domainId);
        getParameters().setDestinationImageId(image.getImageId());

        // ovirt-imageio-daemon must know the boundaries of the target image for writing permissions.
        getParameters().setTransferSize(getTransferSize());

        persistCommand(getParameters().getParentCommand(), true);
        setImage(image);
        setStorageDomainId(domainId);

        log.info("Successfully added {} for image transfer command '{}'",
                getTransferDescription(), getCommandId());

        // ImageGroup is empty when downloading a disk snapshot
        if (!Guid.isNullOrEmpty(getParameters().getImageGroupID())) {
            ImageTransfer updates = new ImageTransfer();
            updates.setDiskId(getParameters().getImageGroupID());
            updateEntity(updates);
        }

        // The image will remain locked until the transfer command has completed.
        lockImage();
        startImageTransferSession();
        log.info("Returning from proceedCommandExecution after starting transfer session"
                + " for image transfer command '{}'", getCommandId());

        resetPeriodicPauseLogTime(0);
    }

    /**
     * direction   storage    transfer    disk     ticket size
     * =================================================================================
     * upload      block      raw         *        virtual size
     * upload      block      -           raw      virtual size (lv size)
     * upload      block      -           qcow2    actual size (lv size)
     * ---------------------------------------------------------------------------------
     * upload      file       raw         *        virtual size
     * upload      file       -           raw      virtual size
     * upload      file       -           qcow2    virtual size + cow overhead
     * ---------------------------------------------------------------------------------
     * upload (ui) *          -           *        uploaded file size
     * ---------------------------------------------------------------------------------
     * download    block      raw         *        virtual size (using /map)
     * download    block      -           raw      virtual size
     * download    block      -           qcow2    image-end-offset (returned by
     *                                             "qemu-img check" - not implemented yet)
     * ---------------------------------------------------------------------------------
     * download    file       raw         *        virtual size (using /map)
     * download    file       -           raw      virtual size
     * download    file       -           qcow2    file size
     * ---------------------------------------------------------------------------------
     */
    private long getTransferSize() {
        DiskImage image = getDiskImage();

        if (getTransferBackend() == ImageTransferBackend.NBD) {
            // NBD always uses virtual size (raw format)
            return image.getSize();
        }

        if (getParameters().getTransferType() == TransferType.Download) {
            if (image.getVolumeFormat() == VolumeFormat.RAW) {
                return image.getSize();
            }
            if (image.getVolumeFormat() == VolumeFormat.COW) {
                return getImageApparentSize(image);
            }
            // Shouldn't happen
            throw new RuntimeException(String.format(
                    "Invalid volume format: %s", image.getVolumeFormat()));
        } else if (getParameters().getTransferType() == TransferType.Upload) {
            if (getParameters().getTransferClientType().isBrowserTransfer()) {
                return getParameters().getTransferSize();
            }
            if (image.getVolumeFormat() == VolumeFormat.RAW) {
                return image.getSize();
            }
            // COW volume format
            boolean isBlockDomain = image.getStorageTypes().get(0).isBlockDomain();
            if (isBlockDomain) {
                return image.getActualSizeInBytes();
            }
            // Needed to allow uploading fully allocated qcow (BZ#1697294)
            // Also, adding qcow header overhead to support small files.
            return (long) Math.ceil(image.getSize() * StorageConstants.QCOW_OVERHEAD_FACTOR)
                    + StorageConstants.QCOW_HEADER_OVERHEAD;
        }
        // Shouldn't happen
        throw new RuntimeException(String.format(
                "Invalid transfer type: %s", getParameters().getTransferType()));
    }

    protected long getImageApparentSize(DiskImage image) {
        Guid domainId = image.getStorageIds().get(0);
        DiskImage imageInfoFromVdsm = imagesHandler.getVolumeInfoFromVdsm(
                image.getStoragePoolId(), domainId, image.getId(), image.getImageId());
        return imageInfoFromVdsm.getApparentSizeInBytes();
    }

    private void handleResuming(final StateContext context) {
        lockImage();

        log.info("Resuming transfer for {}", getTransferDescription());
        auditLog(this, AuditLogType.TRANSFER_IMAGE_RESUMED_BY_USER);
        extendTicketIfNecessary(context);
        updateEntityPhase(ImageTransferPhase.TRANSFERRING);

        resetPeriodicPauseLogTime(0);
    }

    private void handleTransferring(final StateContext context) {
        // While the transfer is in progress, we're responsible
        // for keeping the transfer session alive.
        extendTicketIfNecessary(context);
        resetPeriodicPauseLogTime(0);
        pollTransferStatus(context);
    }

    private void extendTicketIfNecessary(final StateContext context) {
        // The polling interval is user-configurable and grows
        // exponentially, make sure to set it with time to spare.
        if (context.iterationTimestamp
                >= getParameters().getSessionExpiration() - getHostTicketRefreshAllowance()) {
            log.info("Renewing transfer ticket for {}", getTransferDescription());
            boolean extendSucceeded = extendImageTransferSession(context.entity);
            if (!extendSucceeded) {
                log.warn("Failed to renew transfer ticket for {}", getTransferDescription());
                if (getParameters().isRetryExtendTicket()) {
                    // Set 'extendTicketFailed' flag to true for giving a grace period
                    // for another extend attempt.
                    getParameters().setRetryExtendTicket(false);
                } else {
                    updateEntityPhaseToStoppedBySystem(
                            AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_TICKET_RENEW_FAILURE);
                    getParameters().setRetryExtendTicket(true);
                }
            }
        } else {
            log.debug("Not yet renewing transfer ticket for {}", getTransferDescription());
        }
    }

    private void pollTransferStatus(final StateContext context) {
        if (context.entity.getVdsId() == null || context.entity.getImagedTicketId() == null) {
            // Old engines update the transfer status in UploadImageHandler::updateBytesSent.
            return;
        }
        ImageTicketInformation ticketInfo;
        try {
            ticketInfo = (ImageTicketInformation) runVdsCommand(VDSCommandType.GetImageTicket,
                    new GetImageTicketVDSCommandParameters(
                            context.entity.getVdsId(), context.entity.getImagedTicketId())).getReturnValue();
        } catch (EngineException e) {
            log.error("Could not get image ticket '{}' from vdsm", context.entity.getImagedTicketId(), e);
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_MISSING_TICKET);
            return;
        }
        ImageTransfer upToDateImageTransfer = updateTransferStatusWithTicketInformation(context.entity, ticketInfo);
        if (getParameters().getTransferType() == TransferType.Download) {
            finalizeDownloadIfNecessary(context, upToDateImageTransfer);
        }

        // Check conditions for pausing the transfer (ie UI is MIA)
        stopTransferIfNecessary(upToDateImageTransfer, context.iterationTimestamp, ticketInfo.getIdleTime());
    }

    private ImageTransfer updateTransferStatusWithTicketInformation(ImageTransfer oldImageTransfer,
            ImageTicketInformation ticketInfo) {
        if (!Objects.equals(oldImageTransfer.getActive(), ticketInfo.isActive()) ||
                !Objects.equals(oldImageTransfer.getBytesSent(), ticketInfo.getTransferred())) {
            // At least one of the status fields (bytesSent or active) should be updated.
            ImageTransfer updatesFromTicket = new ImageTransfer();
            updatesFromTicket.setBytesSent(ticketInfo.getTransferred());
            updatesFromTicket.setActive(ticketInfo.isActive());
            ActionReturnValue returnValue = runInternalAction(ActionType.TransferImageStatus,
                    new TransferImageStatusParameters(getCommandId(), updatesFromTicket));
            if (returnValue == null || !returnValue.getSucceeded()) {
                log.debug("Failed to update transfer status.");
                return oldImageTransfer;
            }
            return returnValue.getActionReturnValue();
        }
        return oldImageTransfer;
    }

    private void finalizeDownloadIfNecessary(final StateContext context, ImageTransfer upToDateImageTransfer) {
        if (upToDateImageTransfer.getBytesTotal() != 0 &&
                // Frontend flow (REST API should close the connection on its own).
                getParameters().getTransferSize() == upToDateImageTransfer.getBytesSent() &&
                !upToDateImageTransfer.getActive()) {
            // Heuristic - once the transfer is inactive, we want to wait another COCO iteration
            // to decrease the chances that the few last packets are still on the way to the client.
            if (!context.entity.getActive()) { // The entity from the previous COCO iteration.
                // This is the second COCO iteration that the transfer is inactive.
                ImageTransfer statusUpdate = new ImageTransfer();
                statusUpdate.setPhase(ImageTransferPhase.FINALIZING_SUCCESS);
                runInternalAction(ActionType.TransferImageStatus,
                        new TransferImageStatusParameters(getCommandId(), statusUpdate));
            }
        }
    }

    private void handlePausedUser(final StateContext context) {
        auditLog(this, AuditLogType.TRANSFER_IMAGE_PAUSED_BY_USER);
        handlePaused(context);
    }

    private void handlePausedSystem(final StateContext context) {
        handlePaused(context);
    }

    private void handleCancelledUser() {
        log.info("Transfer cancelled by user for {}", getTransferDescription());
        setAuditLogTypeFromPhase(ImageTransferPhase.CANCELLED_USER);
        updateEntityPhase(ImageTransferPhase.FINALIZING_CLEANUP);
    }

    private void handleCancelledSystem() {
        log.info("Transfer cancelled by system for {}", getTransferDescription());
        setAuditLogTypeFromPhase(ImageTransferPhase.CANCELLED_SYSTEM);
        updateEntityPhase(ImageTransferPhase.FINALIZING_FAILURE);
    }

    private void handleFinalizingSuccess(final StateContext context) {
        log.info("Finalizing successful transfer for {}", getTransferDescription());

        ImageStatus nextImageStatus = ImageStatus.OK;

        // If stopping the session did not succeed, don't change the transfer state.
        if (stopImageTransferSession(context.entity)) {
            Guid transferingVdsId = context.entity.getVdsId();
            Guid imageTicketId = context.entity.getImagedTicketId();

            // Stopping NBD server if necessary
            if (getTransferBackend() == ImageTransferBackend.NBD) {
                stopNbdServer(transferingVdsId, imageTicketId);
            }

            // Verify image is relevant only on upload
            if (getParameters().getTransferType() == TransferType.Download) {
                updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
                setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_SUCCESS);
            } else if (verifyImage(transferingVdsId)) {
                // We want to use the transferring vds for image actions for having a coherent log when transferring.
                setVolumeLegalityInStorage(LEGAL_IMAGE);
                if (getDiskImage().getVolumeFormat().equals(VolumeFormat.COW)) {
                    setQcowCompat(getDiskImage().getImage(),
                            getStoragePool().getId(),
                            getDiskImage().getId(),
                            getDiskImage().getImageId(),
                            getStorageDomainId(),
                            transferingVdsId);
                    imageDao.update(getDiskImage().getImage());
                }
                updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
                setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_SUCCESS);
            } else {
                nextImageStatus = ImageStatus.ILLEGAL;
                updateEntityPhase(ImageTransferPhase.FINALIZING_FAILURE);
            }

            // Finished using the image, tear it down.
            tearDownImage(context.entity.getVdsId(), context.entity.getBackupId());

            // Moves Image status to OK or ILLEGAL
            setImageStatus(nextImageStatus);
        }
    }

    private boolean verifyImage(Guid transferingVdsId) {
        ImageActionsVDSCommandParameters parameters =
                new ImageActionsVDSCommandParameters(transferingVdsId, getStoragePool().getId(),
                        getStorageDomainId(),
                        getDiskImage().getId(),
                        getDiskImage().getImageId());

        try {
            // As we currently support a single volume image, we only need to verify that volume.
            vdsBroker.runVdsCommand(VDSCommandType.VerifyUntrustedVolume, parameters);
        } catch (RuntimeException e) {
            log.error("Failed to verify transferred image: {}", e);
            return false;
        }
        return true;
    }

    private void handleFinalizingFailure(final StateContext context) {
        cleanup(context, true);
    }

    private void handleFinalizingCleanup(final StateContext context) {
        cleanup(context, false);
    }

    private void cleanup(final StateContext context, boolean failure) {
        if (failure) {
            log.error("Finalizing failed transfer. {}", getTransferDescription());
        } else {
            log.info("Cleaning up after cancelled transfer. {}", getTransferDescription());
        }
        stopImageTransferSession(context.entity);

        // Stopping NBD server if necessary
        if (getTransferBackend() == ImageTransferBackend.NBD) {
            stopNbdServer(context.entity.getVdsId(), context.entity.getImagedTicketId());
        }

        // Setting disk status to ILLEGAL only on upload failure
        // (only if not disk snapshot)
        if (!Guid.isNullOrEmpty(getParameters().getImageGroupID())) {
            setImageStatus(getParameters().getTransferType() == TransferType.Upload ?
                    ImageStatus.ILLEGAL : ImageStatus.OK);
        }
        Guid vdsId = context.entity.getVdsId() != null ? context.entity.getVdsId() : getVdsId();
        // Teardown is required for all scenarios as we call prepareImage when
        // starting a new session.
        tearDownImage(vdsId, context.entity.getBackupId());
        if (failure) {
            updateEntityPhase(ImageTransferPhase.FINISHED_FAILURE);
            setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_FAILURE);
        } else {
            updateEntityPhase(ImageTransferPhase.FINISHED_CLEANUP);
            setAuditLogTypeFromPhase(ImageTransferPhase.FINISHED_CLEANUP);
        }
    }


    private void handleFinishedSuccess() {
        log.info("Transfer was successful. {}", getTransferDescription());
        setCommandStatus(CommandStatus.SUCCEEDED);
    }

    private void handleFinishedFailure() {
        log.error("Transfer failed. {}", getTransferDescription());
        setCommandStatus(CommandStatus.FAILED);
    }

    private void handleFinishedCleanup() {
        log.info("Cleanup after cancelled transfer done. {}", getTransferDescription());
        setCommandStatus(CommandStatus.FAILED);
    }

    private void handlePaused(final StateContext context) {
        periodicPauseLog(context.entity, context.iterationTimestamp);
    }

    /**
     * Verify conditions for continuing the transfer, stopping it if necessary.
     */
    private void stopTransferIfNecessary(ImageTransfer entity, long ts, Integer idleTimeFromTicket) {
        if (shouldAbortOnClientInactivityTimeout(entity, ts, idleTimeFromTicket)) {
            if (getParameters().getTransferType() == TransferType.Download) {
                // In download flows, we can cancel the transfer if there was no activity
                // for a while, as the download is handled by the client.
                auditLog(this, AuditLogType.DOWNLOAD_IMAGE_CANCELED_TIMEOUT);
                updateEntityPhase(ImageTransferPhase.CANCELLED_SYSTEM);
            } else {
                updateEntityPhaseToStoppedBySystem(
                        AuditLogType.UPLOAD_IMAGE_PAUSED_BY_SYSTEM_TIMEOUT);
            }
        }
    }

    private boolean shouldAbortOnClientInactivityTimeout(ImageTransfer entity, long ts, Integer idleTimeFromTicket) {
        int inactivityTimeout = entity.getClientInactivityTimeout();
        // For new daemon (1.3.0), we check timeout according to 'idle_time' in ticket;
        // otherwise, fallback to check according to entity's 'lastUpdated'.
        boolean timeoutExceeded = idleTimeFromTicket != null ?
                idleTimeFromTicket > inactivityTimeout :
                ts > (entity.getLastUpdated().getTime() / 1000) + inactivityTimeout;
        return inactivityTimeout > 0
                && timeoutExceeded
                && !entity.getActive();
    }

    private void resetPeriodicPauseLogTime(long ts) {
        if (getParameters().getLastPauseLogTime() != ts) {
            getParameters().setLastPauseLogTime(ts);
            persistCommand(getParameters().getParentCommand(), true);
        }
    }

    private void periodicPauseLog(ImageTransfer entity, long ts) {
        if (ts >= getParameters().getLastPauseLogTime() + getPauseLogInterval()) {
            log.info("Transfer was paused by {}. {}",
                    entity.getPhase() == ImageTransferPhase.PAUSED_SYSTEM ? "system" : "user",
                    getTransferDescription());
            resetPeriodicPauseLogTime(ts);
        }
    }

    /**
     * Start the ovirt-image-daemon session
     */
    protected void startImageTransferSession() {
        if (!initializeVds()) {
            log.error("Could not find a suitable host for image data transfer");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_MISSING_HOST);
            return;
        }
        Guid imagedTicketId = Guid.newGuid();
        String imagePath;
        try {
            imagePath = prepareImage(getVdsId(), imagedTicketId);
        } catch (Exception e) {
            log.error("Failed to prepare image for transfer session: {}", e.getMessage(), e);
            return;
        }

        if (!addImageTicketToDaemon(imagedTicketId, imagePath)) {
            log.error("Failed to add image ticket to ovirt-imageio-daemon");
            updateEntityPhaseToStoppedBySystem(
                    AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_ADD_TICKET_TO_DAEMON);
            return;
        }
        if (!addImageTicketToProxy(imagedTicketId, getImageDaemonUri(getVds().getHostName()))) {
            log.error("Failed to add image ticket to ovirt-imageio-proxy");
            if (getParameters().getTransferClientType().isBrowserTransfer()) {
                updateEntityPhaseToStoppedBySystem(
                        AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_ADD_TICKET_TO_PROXY);
                return;
            }
            // No need to stop the transfer - API client can use the daemon url directly.
            auditLog(this, AuditLogType.TRANSFER_FAILED_TO_ADD_TICKET_TO_PROXY);
        }

        log.info("Started transfer session with transfer id {}, timeout {} seconds",
                getParameters().getCommandId().toString(), getClientTicketLifetime());

        ImageTransfer updates = new ImageTransfer();
        updates.setVdsId(getVdsId());
        updates.setImagedTicketId(imagedTicketId);
        updates.setProxyUri(getProxyUri() + IMAGES_PATH);
        updates.setDaemonUri(getImageDaemonUri(getVds().getHostName()) + IMAGES_PATH);
        updateEntity(updates);

        setNewSessionExpiration(getClientTicketLifetime());
        updateEntityPhase(ImageTransferPhase.TRANSFERRING);
    }

    @Override
    protected boolean initializeVds() {
        // In case of downloading a backup disk, the host that runs the VM must handle the transfer
        if (getParameters().getBackupId() != null && getParameters().getTransferType() == TransferType.Download) {
            VmBackup vmBackup = getVmBackup();
            if (vmBackup == null) {
                log.warn("Cannot download disk id: '{}' backup id: '{}' not exist.",
                        getDiskImage().getId(),
                        getParameters().getBackupId());
                return false;
            }

            VM vm = vmDao.get(vmBackup.getVmId());
            VDS vds = vdsDao.get(vm.getRunOnVds());
            if (vds == null) {
                // Can happen when the VM crashed and Not running on any host.
                log.warn("Cannot download disk id: '{}' backup id: '{}', the VM is down.",
                        getDiskImage().getId(),
                        getParameters().getBackupId());
                return false;
            }

            setVds(vds);
            return true;
        }
        return super.initializeVds();
    }

    private boolean addImageTicketToDaemon(Guid imagedTicketId, String imagePath) {
        if (getParameters().getTransferType() == TransferType.Upload &&
                !setVolumeLegalityInStorage(ILLEGAL_IMAGE)) {
            return false;
        }

        ImageTicket ticket = buildImageTicket(imagedTicketId, imagePath);
        AddImageTicketVDSCommandParameters transferCommandParams =
                new AddImageTicketVDSCommandParameters(getVdsId(), ticket);

        // TODO This is called from doPolling(), we should run it async (runFutureVDSCommand?)
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.AddImageTicket, transferCommandParams);
        } catch (RuntimeException e) {
            log.error("Failed to start image transfer session: {}", e.getMessage(), e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.error("Failed to start image transfer session");
            return false;
        }
        log.info("Started transfer session with ticket id {}, timeout {} seconds",
                imagedTicketId.toString(), ticket.getTimeout());

        return true;
    }

    private boolean isSparseImage() {
        // Sparse is not supported yet for block storage in imageio. See BZ#1619006.
        return getDiskImage().getVolumeType() == VolumeType.Sparse &&
                getStorageDomain().getStorageType().isFileDomain();
    }

    private boolean isSupportsDirtyExtents() {
        if (getParameters().getBackupId() == null || getParameters().getTransferType() != TransferType.Download) {
            return false;
        }
        VmBackup vmBackup = getVmBackup();
        return vmBackup != null && vmBackup.isIncremental();
    }

    private boolean addImageTicketToProxy(Guid imagedTicketId, String hostUri) {
        // ToDo: move formatting to an helper for reuse in ImageTransfer
        String url = String.format("%s%s/%s", hostUri, IMAGES_PATH, imagedTicketId);
        ImageTicket imageTicket = buildImageTicket(imagedTicketId, url);

        log.info("Adding image ticket to ovirt-imageio-proxy, id {}", imagedTicketId);
        try {
            getProxyClient().putTicket(imageTicket);
        } catch (RuntimeException e) {
            log.error("Failed to add image ticket to ovirt-imageio-proxy: {}", e.getMessage(), e);
            return false;
        }

        return true;
    }

    private ImageTicket buildImageTicket(Guid ticketId, String ticketUrl) {
        ImageTicket ticket = new ImageTicket();

        ticket.setId(ticketId);
        ticket.setTimeout(getClientTicketLifetime());
        ticket.setSize(getParameters().getTransferSize());
        ticket.setUrl(ticketUrl);
        ticket.setTransferId(getParameters().getCommandId().toString());
        ticket.setFilename(getParameters().getDownloadFilename());
        ticket.setSparse(isSparseImage());
        ticket.setDirty(isSupportsDirtyExtents());
        ticket.setOps(new String[] {getParameters().getTransferType().getAllowedOperation()});

        return ticket;
    }

    private boolean setVolumeLegalityInStorage(boolean legal) {
        SetVolumeLegalityVDSCommandParameters parameters =
                new SetVolumeLegalityVDSCommandParameters(getStoragePool().getId(),
                        getStorageDomainId(),
                        getDiskImage().getId(),
                        getDiskImage().getImageId(),
                        legal);
        try {
            runVdsCommand(VDSCommandType.SetVolumeLegality, parameters);
        } catch (EngineException e) {
            log.error("Failed to set image's volume's legality to {} for image {} and volume {}: {}",
                    legal, getImage().getImage().getDiskId(), getImage().getImageId(), e);
            return false;
        }
        return true;
    }

    @Override
    protected boolean validate() {
        if (isImageProvided()) {
            return validateImageTransfer();
        } else if (getParameters().getTransferType() == TransferType.Download) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_IMAGE_NOT_SPECIFIED_FOR_DOWNLOAD);
        }
        return validateCreateImage();
    }

    private boolean extendImageTransferSession(final ImageTransfer entity) {
        if (entity.getImagedTicketId() == null) {
            log.error("Failed to extend image transfer session: no existing session to extend");
            return false;
        }

        long timeout = getHostTicketLifetime();
        Guid resourceId = entity.getImagedTicketId();

        // Send extend ticket request to vdsm
        ExtendImageTicketVDSCommandParameters transferCommandParams =
                new ExtendImageTicketVDSCommandParameters(entity.getVdsId(), entity.getImagedTicketId(), timeout);
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.ExtendImageTicket, transferCommandParams);
        } catch (RuntimeException e) {
            log.error("Failed to extend image transfer session for ticket '{}': {}",
                    resourceId.toString(), e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.error("Failed to extend image transfer session");
            return false;
        }
        log.info("Transfer session with ticket id {} extended, timeout {} seconds",
                resourceId.toString(), timeout);

        // Send extend ticket request to proxy
        try {
            getProxyClient().extendTicket(resourceId, timeout);
        } catch (RuntimeException e) {
            log.error("Failed to extent image ticket in ovirt-imageio-proxy: {}", e.getMessage(), e);
            if (getParameters().getTransferClientType().isBrowserTransfer()) {
                updateEntityPhaseToStoppedBySystem(
                        AuditLogType.TRANSFER_IMAGE_STOPPED_BY_SYSTEM_FAILED_TO_ADD_TICKET_TO_PROXY);
                return false;
            }
        }

        setNewSessionExpiration(timeout);
        return true;
    }

    private void setNewSessionExpiration(long timeout) {
        getParameters().setSessionExpiration((System.currentTimeMillis() / 1000) + timeout);
        persistCommand(getParameters().getParentCommand(), true);
    }

    private boolean stopImageTransferSession(ImageTransfer entity) {
        if (entity.getImagedTicketId() == null) {
            log.warn("Failed to stop image transfer session. Ticket does not exist for image '{}'", entity.getDiskId());
            return false;
        }

        if (!removeImageTicketFromDaemon(entity.getImagedTicketId(), entity.getVdsId())) {
            return false;
        }
        if (!removeImageTicketFromProxy(entity.getImagedTicketId())) {
            // ignoring when we are not uploading using the browser which
            // always uses the proxy url.
            return !getParameters().getTransferClientType().isBrowserTransfer();
        }

        ImageTransfer updates = new ImageTransfer();
        updateEntity(updates, true);
        return true;
    }

    private boolean removeImageTicketFromDaemon(Guid imagedTicketId, Guid vdsId) {
        RemoveImageTicketVDSCommandParameters parameters = new RemoveImageTicketVDSCommandParameters(
                vdsId, imagedTicketId);
        VDSReturnValue vdsRetVal;
        try {
            vdsRetVal = vdsBroker.runVdsCommand(VDSCommandType.RemoveImageTicket, parameters);
        } catch (RuntimeException e) {
            log.error("Failed to stop image transfer session for ticket '{}': {}", imagedTicketId, e);
            return false;
        }

        if (!vdsRetVal.getSucceeded()) {
            log.warn("Failed to stop image transfer session for ticket '{}'", imagedTicketId);
            return false;
        }
        log.info("Successfully stopped image transfer session for ticket '{}'", imagedTicketId);
        return true;
    }

    private boolean removeImageTicketFromProxy(Guid imagedTicketId) {
        // Send DELETE request
        try {
            log.info("Removing image ticket from ovirt-imageio-proxy, id {}", imagedTicketId);
            getProxyClient().deleteTicket(imagedTicketId);
        } catch (RuntimeException e) {
            log.error("Failed to remove image ticket from ovirt-imageio-proxy: {}", e.getMessage(), e);
            return false;
        }
        return true;
    }

    private void updateEntityPhase(ImageTransferPhase phase) {
        ImageTransfer updates = new ImageTransfer(getCommandId());
        updates.setPhase(phase);
        updateEntity(updates);
    }

    private void updateEntityPhaseToStoppedBySystem(AuditLogType stoppedBySystemReason) {
        auditLog(this, stoppedBySystemReason);
        if (getParameters().getTransferType() == TransferType.Upload) {
            updateEntityPhase(ImageTransferPhase.PAUSED_SYSTEM);
        } else {
            updateEntityPhase(ImageTransferPhase.CANCELLED_SYSTEM);
        }
    }

    private void updateEntity(ImageTransfer updates) {
        updateEntity(updates, false);
    }

    private void updateEntity(ImageTransfer updates, boolean clearResourceId) {
        imageTransferUpdater.updateEntity(updates, getCommandId(), clearResourceId);
    }

    private int getHostTicketRefreshAllowance() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferHostTicketRefreshAllowanceInSeconds);
    }

    private int getHostTicketLifetime() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferHostTicketValidityInSeconds);
    }

    private int getClientTicketLifetime() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferClientTicketValidityInSeconds);
    }

    private int getPauseLogInterval() {
        return Config.<Integer>getValue(ConfigValues.ImageTransferPausedLogIntervalInSeconds);
    }

    private int getTransferImageClientInactivityTimeoutInSeconds() {
        return Config.<Integer>getValue(ConfigValues.TransferImageClientInactivityTimeoutInSeconds);
    }

    public static String getProxyUri() {
        String scheme = Config.<Boolean> getValue(ConfigValues.ImageProxySSLEnabled)?  HTTPS_SCHEME : HTTP_SCHEME;
        return scheme + EngineLocalConfig.getInstance().getHost() + ":" + PROXY_DATA_PORT;
    }

    private String getImageDaemonUri(String daemonHostname) {
        String port = Config.getValue(ConfigValues.ImageDaemonPort);
        return HTTPS_SCHEME + daemonHostname + ":" + port;
    }

    private void setAuditLogTypeFromPhase(ImageTransferPhase phase) {
        if (getParameters().getAuditLogType() != null) {
            // Some flows, e.g. cancellation, may set the log type more than once.
            // In this case, the first type is the most accurate.
            return;
        }

        if (phase == ImageTransferPhase.FINISHED_SUCCESS) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_SUCCEEDED);
        } else if (phase == ImageTransferPhase.CANCELLED_SYSTEM || phase == ImageTransferPhase.CANCELLED_USER) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_CANCELLED);
        } else if (phase == ImageTransferPhase.FINISHED_FAILURE) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_FAILED);
        } else if (phase == ImageTransferPhase.FINISHED_CLEANUP) {
            getParameters().setAuditLogType(AuditLogType.TRANSFER_IMAGE_CLEANED_UP);
        }
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        addCustomValue("DiskAlias", getImageAlias());
        addCustomValue("TransferType", getParameters().getTransferType().name());
        return getActionState() == CommandActionState.EXECUTE
                ? AuditLogType.TRANSFER_IMAGE_INITIATED : getParameters().getAuditLogType();
    }

    // Return a string describing the transfer, safe for use before the new image
    // is successfully created; e.g. "disk 'NewDisk' (id '<uuid>')".
    private String getTransferDescription() {
        return String.format("%s %s '%s' (disk id: '%s', image id: '%s')",
                getParameters().getTransferType().name(),
                IMAGE_TYPE,
                getImageAlias(),
                getDiskImage().getId(),
                getDiskImage().getImageId());
    }

    private ImageioClient getProxyClient() {
        if (proxyClient == null) {
            proxyClient = new ImageioClient("localhost", PROXY_CONTROL_PORT);
        }
        return proxyClient;
    }

    public void onSucceeded() {
        updateEntityPhase(ImageTransferPhase.FINISHED_SUCCESS);
        endSuccessfully();
        log.info("Successfully transferred disk '{}' (command id '{}')",
                getParameters().getImageId(), getCommandId());
    }

    public void onFailed() {
        updateEntityPhase(ImageTransferPhase.FINISHED_FAILURE);
        endWithFailure();
        log.error("Failed to transfer disk '{}' (command id '{}')",
                getParameters().getImageId(), getCommandId());
    }

    @Override
    protected void endSuccessfully() {
        if (getParameters().getTransferType() == TransferType.Upload) {
            // Update image data in DB, set Qcow Compat, etc
            // (relevant only for upload)
            super.endSuccessfully();
        }
        setSucceeded(true);
    }

    @Override
    protected void endWithFailure() {
        if (getParameters().getTransferType() == TransferType.Upload) {
            // Do rollback only for upload - i.e. remove the disk added in 'createImage()'
            runInternalAction(ActionType.RemoveDisk, new RemoveDiskParameters(getParameters().getImageGroupID()));
        }
        setSucceeded(true);
    }

    @Override
    public CommandCallback getCallback() {
        return callbackProvider.get();
    }

    @Override
    protected LockProperties applyLockProperties(LockProperties lockProperties) {
        return lockProperties.withScope(LockProperties.Scope.Command);
    }

    // Container for context needed by state machine handlers
    class StateContext {
        ImageTransfer entity;
        long iterationTimestamp;
        Guid childCmdId;
    }
}
