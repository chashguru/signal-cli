package org.asamk.signal.manager.helper;

import org.asamk.signal.manager.DeviceLinkInfo;
import org.asamk.signal.manager.SignalDependencies;
import org.asamk.signal.manager.api.CaptchaRequiredException;
import org.asamk.signal.manager.api.IncorrectPinException;
import org.asamk.signal.manager.api.InvalidDeviceLinkException;
import org.asamk.signal.manager.api.PinLockedException;
import org.asamk.signal.manager.config.ServiceConfig;
import org.asamk.signal.manager.storage.SignalAccount;
import org.asamk.signal.manager.util.KeyUtils;
import org.asamk.signal.manager.util.NumberVerificationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.ACI;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.util.DeviceNameUtil;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class AccountHelper {

    private final static Logger logger = LoggerFactory.getLogger(AccountHelper.class);

    private final Context context;
    private final SignalAccount account;
    private final SignalDependencies dependencies;

    private Callable unregisteredListener;

    public AccountHelper(final Context context) {
        this.account = context.getAccount();
        this.dependencies = context.getDependencies();
        this.context = context;
    }

    public void setUnregisteredListener(final Callable unregisteredListener) {
        this.unregisteredListener = unregisteredListener;
    }

    public void checkAccountState() throws IOException {
        if (account.getLastReceiveTimestamp() == 0) {
            logger.info("The Signal protocol expects that incoming messages are regularly received.");
        } else {
            var diffInMilliseconds = System.currentTimeMillis() - account.getLastReceiveTimestamp();
            long days = TimeUnit.DAYS.convert(diffInMilliseconds, TimeUnit.MILLISECONDS);
            if (days > 7) {
                logger.warn(
                        "Messages have been last received {} days ago. The Signal protocol expects that incoming messages are regularly received.",
                        days);
            }
        }
        try {
            context.getPreKeyHelper().refreshPreKeysIfNecessary();
            if (account.getAci() == null) {
                checkWhoAmiI();
            }
            updateAccountAttributes();
        } catch (AuthorizationFailedException e) {
            account.setRegistered(false);
            throw e;
        }
    }

    public void checkWhoAmiI() throws IOException {
        final var whoAmI = dependencies.getAccountManager().getWhoAmI();
        final var number = whoAmI.getNumber();
        final var aci = ACI.parseOrNull(whoAmI.getAci());
        if (number.equals(account.getNumber()) && aci.equals(account.getAci())) {
            return;
        }

        updateSelfIdentifiers(number, aci);
    }

    private void updateSelfIdentifiers(final String number, final ACI aci) {
        account.setNumber(number);
        account.setAci(aci);
        account.getRecipientStore().resolveSelfRecipientTrusted(account.getSelfRecipientAddress());
        // TODO check and update remote storage
        context.getUnidentifiedAccessHelper().rotateSenderCertificates();
        dependencies.resetAfterAddressChange();
        dependencies.getSignalWebSocket().forceNewWebSockets();
        context.getAccountFileUpdater().updateAccountIdentifiers(account.getNumber(), account.getAci());
    }

    public void startChangeNumber(
            String newNumber, String captcha, boolean voiceVerification
    ) throws IOException, CaptchaRequiredException {
        final var accountManager = dependencies.createUnauthenticatedAccountManager(newNumber, account.getPassword());
        NumberVerificationUtils.requestVerificationCode(accountManager, captcha, voiceVerification);
    }

    public void finishChangeNumber(
            String newNumber, String verificationCode, String pin
    ) throws IncorrectPinException, PinLockedException, IOException {
        final var result = NumberVerificationUtils.verifyNumber(verificationCode,
                pin,
                context.getPinHelper(),
                (verificationCode1, registrationLock) -> dependencies.getAccountManager()
                        .changeNumber(verificationCode1, newNumber, registrationLock));
        // TODO handle response
        updateSelfIdentifiers(newNumber, account.getAci());
    }

    public void setDeviceName(String deviceName) {
        final var privateKey = account.getIdentityKeyPair().getPrivateKey();
        final var encryptedDeviceName = DeviceNameUtil.encryptDeviceName(deviceName, privateKey);
        account.setEncryptedDeviceName(encryptedDeviceName);
    }

    public void updateAccountAttributes() throws IOException {
        dependencies.getAccountManager()
                .setAccountAttributes(null,
                        account.getLocalRegistrationId(),
                        true,
                        null,
                        account.getPinMasterKey() == null ? null : account.getPinMasterKey().deriveRegistrationLock(),
                        account.getSelfUnidentifiedAccessKey(),
                        account.isUnrestrictedUnidentifiedAccess(),
                        ServiceConfig.capabilities,
                        account.isDiscoverableByPhoneNumber(),
                        account.getEncryptedDeviceName());
    }

    public void addDevice(DeviceLinkInfo deviceLinkInfo) throws IOException, InvalidDeviceLinkException {
        var identityKeyPair = account.getIdentityKeyPair();
        var verificationCode = dependencies.getAccountManager().getNewDeviceVerificationCode();

        try {
            dependencies.getAccountManager()
                    .addDevice(deviceLinkInfo.deviceIdentifier(),
                            deviceLinkInfo.deviceKey(),
                            identityKeyPair,
                            Optional.of(account.getProfileKey().serialize()),
                            verificationCode);
        } catch (InvalidKeyException e) {
            throw new InvalidDeviceLinkException("Invalid device link", e);
        }
        account.setMultiDevice(true);
    }

    public void removeLinkedDevices(int deviceId) throws IOException {
        dependencies.getAccountManager().removeDevice(deviceId);
        var devices = dependencies.getAccountManager().getDevices();
        account.setMultiDevice(devices.size() > 1);
    }

    public void setRegistrationPin(String pin) throws IOException {
        final var masterKey = account.getPinMasterKey() != null
                ? account.getPinMasterKey()
                : KeyUtils.createMasterKey();

        context.getPinHelper().setRegistrationLockPin(pin, masterKey);

        account.setRegistrationLockPin(pin, masterKey);
    }

    public void removeRegistrationPin() throws IOException {
        // Remove KBS Pin
        context.getPinHelper().removeRegistrationLockPin();

        account.setRegistrationLockPin(null, null);
    }

    public void unregister() throws IOException {
        // When setting an empty GCM id, the Signal-Server also sets the fetchesMessages property to false.
        // If this is the master device, other users can't send messages to this number anymore.
        // If this is a linked device, other users can still send messages, but this device doesn't receive them anymore.
        dependencies.getAccountManager().setGcmId(Optional.absent());

        account.setRegistered(false);
        unregisteredListener.call();
    }

    public void deleteAccount() throws IOException {
        try {
            context.getPinHelper().removeRegistrationLockPin();
        } catch (IOException e) {
            logger.warn("Failed to remove registration lock pin");
        }
        account.setRegistrationLockPin(null, null);

        dependencies.getAccountManager().deleteAccount();

        account.setRegistered(false);
        unregisteredListener.call();
    }

    public interface Callable {

        void call();
    }
}
