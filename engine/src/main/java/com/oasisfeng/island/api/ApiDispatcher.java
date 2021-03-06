package com.oasisfeng.island.api;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.Nullable;

import com.oasisfeng.island.engine.IslandManager;
import com.oasisfeng.island.util.DevicePolicies;
import com.oasisfeng.island.util.Hacks;
import com.oasisfeng.island.util.Permissions;
import com.oasisfeng.island.util.Users;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static android.content.pm.PackageManager.GET_SIGNATURES;
import static android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES;
import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.oasisfeng.island.util.Permissions.INTERACT_ACROSS_USERS;

/**
 * Dispatch the API calls.
 *
 * Created by Oasis on 2017/9/20.
 */
class ApiDispatcher {

	/**
	 * Verify the qualification of this API invocation, mainly the permission of API caller.
	 *
	 * @param pkg the package name of API caller, if known.
	 * @param uid the UID of API caller, not used if caller_pkg is null. (negative if unavailable)
	 * @return null if verified, or error message for debugging purpose (NOT part of the API protocol).
	 */
	static String verifyCaller(final Context context, final Intent intent, @Nullable String pkg, int uid) {
		if (pkg == null) {
			final PendingIntent id = intent.getParcelableExtra(Api.latest.EXTRA_CALLER_ID);
			if (id == null) return "Missing required PendingIntent extra: " + Api.latest.EXTRA_CALLER_ID;
			pkg = id.getCreatorPackage();
			uid = id.getCreatorUid();
			if (pkg == null) return "No creator information in " + id;
		}
		if (uid > 0 && Users.isSameApp(uid, Process.myUid())) return null;		// From myself (possibly in other user).
		// This log should generally be placed in the caller site, do it after same app check to skip this for internal API caller with component set.
		if (intent.getPackage() == null && intent.getComponent() != null)
			Log.w(TAG, "Never use implicit intent or explicit intent with component name for API request, use Intent.setPackage() instead.");

		Log.d(TAG, "API invoked by " + pkg);
		if (uid >= 0 && verifyRuntimePermission(context, uid, intent.getAction())) return null;

		// Fallback verification for API v1 clients.
		final Integer value = sVerifiedCallers.get(pkg);
		if (value == null) return "Unauthorized client: " + pkg;
		final int signature_hash = value;
		if (signature_hash == 0) return null;	// 0 means already verified (cached)

		// Legacy verification is not supported inside Island without INTERACT_ACROSS_USERS on Android P+, due to MATCH_ANY_USER being restricted.
		try { @SuppressLint("WrongConstant")
			final PackageInfo pkg_info = context.getPackageManager().getPackageInfo(pkg, GET_SIGNATURES
				| (SDK_INT < P || Permissions.has(context, INTERACT_ACROSS_USERS) ? Hacks.GET_ANY_USER_AND_UNINSTALLED : MATCH_UNINSTALLED_PACKAGES));
			return verifySignature(pkg, signature_hash, pkg_info);
		} catch (final PackageManager.NameNotFoundException e) { return "Permission denied or client package not found: " + pkg; }
	}

	@Nullable private static String verifySignature(final String pkg, final int signature_hash, final PackageInfo pkg_info) {
		for (final Signature signature : pkg_info.signatures)
			if (signature.hashCode() != signature_hash) return "Package signature mismatch";
		sVerifiedCallers.put(pkg, 0);		// No further signature check for this caller in the lifetime of this process.
		return null;
	}

	private static boolean verifyRuntimePermission(final Context context, final int uid, final String action) {
		if (action == null) return false;
		final String permission;
		switch (action) {
		case Api.latest.ACTION_FREEZE:
		case Api.latest.ACTION_UNFREEZE:
			permission = Api.latest.PERMISSION_FREEZE_PACKAGE;	break;
		case Api.latest.ACTION_LAUNCH:
			permission = Api.latest.PERMISSION_LAUNCH_PACKAGE;	break;
		case Api.latest.ACTION_SUSPEND:
		case Api.latest.ACTION_UNSUSPEND:
			permission = Api.latest.PERMISSION_SUSPEND_PACKAGE;	break;
		default: return false;
		}
		return context.checkPermission(permission, 0, uid) == PackageManager.PERMISSION_GRANTED;
	}

	/** @return null for success, or error message for debugging purpose (NOT part of the API protocol). */
	static String dispatch(final Context context, final Intent intent) {
		final String action = intent.getAction();
		if (action == null) return "No action";
		boolean positive = false;
		switch (action) {
		case Api.latest.ACTION_FREEZE: positive = true;		// Fall-through
		case Api.latest.ACTION_UNFREEZE:
			final boolean hidden = positive;
			return processPackageUri(intent, null, pkg -> IslandManager.ensureAppHiddenState(context, pkg, hidden));
		case Api.latest.ACTION_LAUNCH:
			return launchActivity(context, intent);
		case Api.latest.ACTION_SUSPEND: positive = true;	// Fall-through
		case Api.latest.ACTION_UNSUSPEND:
			final boolean suspended = positive;
			return processPackageUri(intent, pkgs -> setPackageSuspended(context, pkgs, suspended), null);
		default: return "Unsupported action: " + action;
		}
	}

	private static String launchActivity(final Context context, final Intent intent) {
		final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);
		if (user != null) {
			intent.removeExtra(Intent.EXTRA_USER);
			context.sendOrderedBroadcastAsUser(intent, user, null, null, null, 0, null, null);
			return null;
		}

		final Uri uri = intent.getData();
		if (uri == null) return "No data in Intent: " + intent;
		final String scheme = uri.getScheme();

		if ("package".equals(scheme)) {
			final String pkg = uri.getSchemeSpecificPart();
			final String free_to_launch = IslandManager.ensureAppFreeToLaunch(context, pkg);
			if (! free_to_launch.isEmpty()) return free_to_launch;
			return IslandManager.launchApp(context, pkg, Process.myUserHandle()) ? null : "no_launcher_activity";
		}

		if (! "intent".equals(scheme)) return "Unsupported intent data scheme: " + intent;
		final Intent target;
		try {
			target = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
		} catch (final URISyntaxException e) {
			return "Invalid data in intent: " + intent;
		}
		if (! (context instanceof Activity)) target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		try {
			context.startActivity(target);
		} catch (final ActivityNotFoundException e) {
			return e.toString();
		}
		return null;
	}

	private static String setPackageSuspended(final Context context, final Stream<String> pkgs, final boolean suspended) {
		final String[] failed = new DevicePolicies(context).invoke(DevicePolicyManager::setPackagesSuspended, pkgs.toArray(String[]::new), suspended);
		return failed.length == 0 ? null : "Failed packages: " + Arrays.toString(failed);
	}

	private static String processPackageUri(final Intent intent, final Function<Stream<String>, String> batch_dealer, final Predicate<String> dealer) {
		final Uri uri = intent.getData();
		final String ssp;
		if (uri == null || (ssp = uri.getSchemeSpecificPart()) == null) return "Invalid data in Intent: " + intent;
		final String scheme = uri.getScheme();
		final Stream<String> pkgs;
		final boolean single;
		if (single = "package".equals(scheme)) pkgs = Stream.of(ssp);
		else if ("packages".equals(scheme)) pkgs = Arrays.stream(ssp.split(",")).filter(Objects::nonNull).map(String::trim);
		else return "Unsupported intent data scheme: " + intent;	// Should never happen

		try {
			if (batch_dealer != null) return batch_dealer.apply(pkgs);

			final List<String> failed_pkgs = pkgs.filter(t -> ! dealer.test(t)).collect(Collectors.toList());
			if (failed_pkgs.isEmpty()) return null;
			if (single) return "Failed: " + ssp;
			return "Failed: " + failed_pkgs;
		} catch (final RuntimeException e) {
			return "Internal exception: " + e;		// Island might be have been deactivated or not set up yet.
		}
	}

	private static final Map<String/* pkg */, Integer/* signature hash */> sVerifiedCallers = new ArrayMap<>(2);
	static {
		sVerifiedCallers.put("com.oasisfeng.greenify", -373128424);
		sVerifiedCallers.put("com.catchingnow.icebox", -502198281);
	}

	private static final String TAG = "API";
}
