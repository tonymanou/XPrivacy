package biz.bokhorst.xprivacy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.saurik.substrate.MS;

import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import static de.robv.android.xposed.XposedHelpers.findClass;

// TODO: fix link error when using Cydia Substrate
public class XPrivacy implements IXposedHookLoadPackage, IXposedHookZygoteInit {
	private static boolean mCydia = false;
	private static String mSecret = null;
	private static List<String> mListHookError = new ArrayList<String>();
	private static List<CRestriction> mListDisabled = new ArrayList<CRestriction>();

	// http://developer.android.com/reference/android/Manifest.permission.html

	static {
		if (mListDisabled.size() == 0) {
			File disabled = new File("/data/system/xprivacy/disabled");
			if (disabled.exists() && disabled.canRead())
				try {
					Log.w("XPrivacy", "Reading " + disabled.getAbsolutePath());
					FileInputStream fis = new FileInputStream(disabled);
					InputStreamReader ir = new InputStreamReader(fis);
					BufferedReader br = new BufferedReader(ir);
					String line;
					while ((line = br.readLine()) != null) {
						String[] name = line.split("/");
						if (name.length > 0) {
							String methodName = (name.length > 1 ? name[1] : null);
							CRestriction restriction = new CRestriction(0, name[0], methodName, null);
							Log.w("XPrivacy", "Disabling " + restriction);
							mListDisabled.add(restriction);
						}
					}
					br.close();
					ir.close();
					fis.close();
				} catch (Throwable ex) {
					Log.w("XPrivacy", ex.toString());
				}
		}
	}

	// Xposed
	public void initZygote(StartupParam startupParam) throws Throwable {
		// Check for LBE security master
		if (Util.hasLBE()) {
			Util.log(null, Log.ERROR, "LBE installed");
			return;
		}

		init(startupParam.modulePath);
	}

	public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
		// Check for LBE security master
		if (Util.hasLBE())
			return;

		handleLoadPackage(lpparam.packageName, lpparam.classLoader, mSecret);
	}

	// Cydia
	public static void initialize() {
		mCydia = true;
		init(null);

		// Self
		MS.hookClassLoad(Util.class.getName(), new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XUtilHook.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// TODO: Cydia: Build.SERIAL
		// TODO: Cydia: android.provider.Settings.Secure
		// TODO: Cydia: Phone instances

		// Providers
		for (final String className : XContentResolver.cProviderClassName)
			MS.hookClassLoad(className, new MS.ClassLoadHook() {
				@Override
				public void classLoaded(Class<?> clazz) {
					hookAll(XContentResolver.getInstances(className), clazz.getClassLoader(), mSecret);
				}
			});

		// Advertising Id
		MS.hookClassLoad("com.google.android.gms.ads.identifier.AdvertisingIdClient", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XAdvertisingIdClientInfo.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// User activity
		MS.hookClassLoad("com.google.android.gms.location.ActivityRecognitionClient", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XActivityRecognitionClient.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// GoogleApiClient.Builder
		MS.hookClassLoad("com.google.android.gms.common.api.GoogleApiClient", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XGoogleApiClient.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// Google auth
		MS.hookClassLoad("com.google.android.gms.auth.GoogleAuthUtil", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XGoogleAuthUtil.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// Location client
		MS.hookClassLoad("com.google.android.gms.location.LocationClient", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XLocationClient.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// Google Map V1
		MS.hookClassLoad("com.google.android.maps.GeoPoint", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XGoogleMapV1.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});

		// Google Map V2
		MS.hookClassLoad("com.google.android.gms.maps.GoogleMap", new MS.ClassLoadHook() {
			@Override
			public void classLoaded(Class<?> clazz) {
				hookAll(XGoogleMapV2.getInstances(), clazz.getClassLoader(), mSecret);
			}
		});
	}

	// Common
	private static void init(String path) {
		Util.log(null, Log.WARN, "Init path=" + path);

		// Generate secret
		mSecret = Long.toHexString(new Random().nextLong());

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
			try {
				Class<?> libcore = Class.forName("libcore.io.Libcore");
				Field fOs = libcore.getDeclaredField("os");
				fOs.setAccessible(true);
				Object os = fOs.get(null);
				Method setenv = os.getClass().getMethod("setenv", String.class, String.class, boolean.class);
				setenv.setAccessible(true);
				boolean aosp = new File("/data/system/xprivacy/aosp").exists();
				setenv.invoke(os, "XPrivacy.AOSP", Boolean.toString(aosp), false);
				Util.log(null, Log.WARN, "AOSP mode forced=" + aosp);
			} catch (Throwable ex) {
				Util.bug(null, ex);
			}

		// System server
		try {
			// frameworks/base/services/java/com/android/server/SystemServer.java
			Class<?> cSystemServer = Class.forName("com.android.server.SystemServer");
			Method mMain = cSystemServer.getDeclaredMethod("main", String[].class);
			if (mCydia)
				MS.hookMethod(cSystemServer, mMain, new MS.MethodAlteration<Object, Void>() {
					@Override
					public Void invoked(Object thiz, Object... args) throws Throwable {
						PrivacyService.register(mListHookError, mSecret);
						return invoke(thiz, args);
					}
				});
			else
				XposedBridge.hookMethod(mMain, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						PrivacyService.register(mListHookError, mSecret);
					}
				});
		} catch (Throwable ex) {
			Util.bug(null, ex);
		}

		// Account manager
		hookAll(XAccountManager.getInstances(null), null, mSecret);

		// Activity manager
		hookAll(XActivityManager.getInstances(null), null, mSecret);

		// Activity manager service
		hookAll(XActivityManagerService.getInstances(), null, mSecret);

		// App widget manager
		hookAll(XAppWidgetManager.getInstances(), null, mSecret);

		// Application
		hookAll(XApplication.getInstances(), null, mSecret);

		// Audio record
		hookAll(XAudioRecord.getInstances(), null, mSecret);

		// Binder device
		hookAll(XBinder.getInstances(), null, mSecret);

		// Bluetooth adapater
		hookAll(XBluetoothAdapter.getInstances(), null, mSecret);

		// Bluetooth device
		hookAll(XBluetoothDevice.getInstances(), null, mSecret);

		// Camera
		hookAll(XCamera.getInstances(), null, mSecret);

		// Camera2 device
		hookAll(XCameraDevice2.getInstances(), null, mSecret);

		// Clipboard manager
		hookAll(XClipboardManager.getInstances(null), null, mSecret);

		// Connectivity manager
		hookAll(XConnectivityManager.getInstances(null), null, mSecret);

		// Content resolver
		hookAll(XContentResolver.getInstances(null), null, mSecret);

		// Context wrapper
		hookAll(XContextImpl.getInstances(), null, mSecret);

		// Environment
		hookAll(XEnvironment.getInstances(), null, mSecret);

		// Inet address
		hookAll(XInetAddress.getInstances(), null, mSecret);

		// Input device
		hookAll(XInputDevice.getInstances(), null, mSecret);

		// Intent firewall
		hookAll(XIntentFirewall.getInstances(), null, mSecret);

		// IO bridge
		hookAll(XIoBridge.getInstances(), null, mSecret);

		// Location manager
		hookAll(XLocationManager.getInstances(null), null, mSecret);

		// Media recorder
		hookAll(XMediaRecorder.getInstances(), null, mSecret);

		// Network info
		hookAll(XNetworkInfo.getInstances(), null, mSecret);

		// Network interface
		hookAll(XNetworkInterface.getInstances(), null, mSecret);

		// NFC adapter
		hookAll(XNfcAdapter.getInstances(), null, mSecret);

		// Package manager service
		hookAll(XPackageManager.getInstances(null), null, mSecret);

		// Process
		hookAll(XProcess.getInstances(), null, mSecret);

		// Process builder
		hookAll(XProcessBuilder.getInstances(), null, mSecret);

		// Resources
		hookAll(XResources.getInstances(), null, mSecret);

		// Runtime
		hookAll(XRuntime.getInstances(), null, mSecret);

		// Sensor manager
		hookAll(XSensorManager.getInstances(null), null, mSecret);

		// Settings secure
		if (!mCydia)
			hookAll(XSettingsSecure.getInstances(), null, mSecret);

		// SIP manager
		hookAll(XSipManager.getInstances(), null, mSecret);

		// SMS manager
		hookAll(XSmsManager.getInstances(), null, mSecret);

		// System properties
		hookAll(XSystemProperties.getInstances(), null, mSecret);

		// Telephone service
		hookAll(XTelephonyManager.getInstances(null), null, mSecret);

		// USB device
		hookAll(XUsbDevice.getInstances(), null, mSecret);

		// Web view
		hookAll(XWebView.getInstances(), null, mSecret);

		// Window service
		hookAll(XWindowManager.getInstances(null), null, mSecret);

		// Wi-Fi service
		hookAll(XWifiManager.getInstances(null), null, mSecret);

		// Intent receive
		hookAll(XActivityThread.getInstances(), null, mSecret);

		// Intent send
		hookAll(XActivity.getInstances(), null, mSecret);
	}

	private static void handleLoadPackage(String packageName, ClassLoader classLoader, String secret) {
		Util.log(null, Log.INFO, "Load package=" + packageName + " uid=" + Process.myUid());

		// Skip hooking self
		String self = XPrivacy.class.getPackage().getName();
		if (packageName.equals(self)) {
			hookAll(XUtilHook.getInstances(), classLoader, secret);
			return;
		}

		// Build SERIAL
		if (PrivacyManager.getRestrictionExtra(null, Process.myUid(), PrivacyManager.cIdentification, "SERIAL", null,
				Build.SERIAL, secret))
			try {
				Field serial = Build.class.getField("SERIAL");
				serial.setAccessible(true);
				serial.set(null, PrivacyManager.getDefacedProp(Process.myUid(), "SERIAL"));
			} catch (Throwable ex) {
				Util.bug(null, ex);
			}

		// Activity recognition
		try {
			Class.forName("com.google.android.gms.location.ActivityRecognitionClient", false, classLoader);
			hookAll(XActivityRecognitionClient.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Advertising Id
		try {
			Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", false, classLoader);
			hookAll(XAdvertisingIdClientInfo.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Google auth
		try {
			Class.forName("com.google.android.gms.auth.GoogleAuthUtil", false, classLoader);
			hookAll(XGoogleAuthUtil.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// GoogleApiClient.Builder
		try {
			Class.forName("com.google.android.gms.common.api.GoogleApiClient$Builder", false, classLoader);
			hookAll(XGoogleApiClient.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Google Map V1
		try {
			Class.forName("com.google.android.maps.GeoPoint", false, classLoader);
			hookAll(XGoogleMapV1.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Google Map V2
		try {
			Class.forName("com.google.android.gms.maps.GoogleMap", false, classLoader);
			hookAll(XGoogleMapV2.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Location client
		try {
			Class.forName("com.google.android.gms.location.LocationClient", false, classLoader);
			hookAll(XLocationClient.getInstances(), classLoader, secret);
		} catch (Throwable ignored) {
		}

		// Phone interface manager
		if ("com.android.phone".equals(packageName))
			hookAll(XTelephonyManager.getPhoneInstances(), classLoader, secret);

		// Providers
		hookAll(XContentResolver.getPackageInstances(packageName, classLoader), classLoader, secret);
	}

	public static void handleGetSystemService(String name, String className, String secret) {
		if (PrivacyManager.getTransient(className, null) == null) {
			PrivacyManager.setTransient(className, Boolean.toString(true));

			if (name.equals(Context.ACCOUNT_SERVICE))
				hookAll(XAccountManager.getInstances(className), null, secret);
			else if (name.equals(Context.ACTIVITY_SERVICE))
				hookAll(XActivityManager.getInstances(className), null, secret);
			else if (name.equals(Context.CLIPBOARD_SERVICE))
				hookAll(XClipboardManager.getInstances(className), null, secret);
			else if (name.equals(Context.CONNECTIVITY_SERVICE))
				hookAll(XConnectivityManager.getInstances(className), null, secret);
			else if (name.equals(Context.LOCATION_SERVICE))
				hookAll(XLocationManager.getInstances(className), null, secret);
			else if (name.equals("PackageManager"))
				hookAll(XPackageManager.getInstances(className), null, secret);
			else if (name.equals(Context.SENSOR_SERVICE))
				hookAll(XSensorManager.getInstances(className), null, secret);
			else if (name.equals(Context.TELEPHONY_SERVICE))
				hookAll(XTelephonyManager.getInstances(className), null, secret);
			else if (name.equals(Context.WINDOW_SERVICE))
				hookAll(XWindowManager.getInstances(className), null, secret);
			else if (name.equals(Context.WIFI_SERVICE))
				hookAll(XWifiManager.getInstances(className), null, secret);
		}
	}

	public static void hookAll(List<XHook> listHook, ClassLoader classLoader, String secret) {
		for (XHook hook : listHook)
			if (hook.getRestrictionName() == null)
				hook(hook, classLoader, secret);
			else {
				CRestriction crestriction = new CRestriction(0, hook.getRestrictionName(), null, null);
				CRestriction mrestriction = new CRestriction(0, hook.getRestrictionName(), hook.getMethodName(), null);
				if (mListDisabled.contains(crestriction) || mListDisabled.contains(mrestriction))
					Util.log(hook, Log.WARN, "Skipping " + hook);
				else
					hook(hook, classLoader, secret);
			}
	}

	private static void hook(final XHook hook, ClassLoader classLoader, String secret) {
		// Get meta data
		Hook md = PrivacyManager.getHook(hook.getRestrictionName(), hook.getSpecifier());
		if (md == null) {
			String message = "Not found hook=" + hook;
			mListHookError.add(message);
			Util.log(hook, Log.ERROR, message);
		} else if (!md.isAvailable())
			return;

		// Provide secret
		if (secret == null)
			Util.log(hook, Log.ERROR, "Secret missing hook=" + hook);
		hook.setSecret(secret);

		try {
			// Find class
			Class<?> hookClass = null;
			try {
				if (mCydia)
					hookClass = Class.forName(hook.getClassName(), false, classLoader);
				else
					hookClass = findClass(hook.getClassName(), classLoader);
			} catch (Throwable ex) {
				String message = "Class not found hook=" + hook;
				mListHookError.add(message);
				int level = (md != null && md.isOptional() ? Log.WARN : Log.ERROR);
				Util.log(hook, level, message);
				Util.logStack(hook, level);
			}

			// Get members
			List<Member> listMember = new ArrayList<Member>();
			// TODO: enable/disable superclass traversal
			Class<?> clazz = hookClass;
			while (clazz != null && !"android.content.ContentProvider".equals(clazz.getName()))
				try {
					if (hook.getMethodName() == null) {
						for (Constructor<?> constructor : clazz.getDeclaredConstructors())
							if (!Modifier.isAbstract(constructor.getModifiers())
									&& Modifier.isPublic(constructor.getModifiers()) ? hook.isVisible() : !hook
									.isVisible())
								listMember.add(constructor);
						break;
					} else {
						for (Method method : clazz.getDeclaredMethods())
							if (method.getName().equals(hook.getMethodName())
									&& !Modifier.isAbstract(method.getModifiers())
									&& (Modifier.isPublic(method.getModifiers()) ? hook.isVisible() : !hook.isVisible()))
								listMember.add(method);
					}
					clazz = clazz.getSuperclass();
				} catch (Throwable ex) {
					if (ex.getClass().equals(ClassNotFoundException.class))
						break;
					else
						throw ex;
				}

			// Hook members
			for (Member member : listMember)
				try {
					if (mCydia) {
						XMethodAlteration alteration = new XMethodAlteration(hook, member);
						if (member instanceof Method)
							MS.hookMethod(member.getDeclaringClass(), (Method) member, alteration);
						else
							MS.hookMethod(member.getDeclaringClass(), (Constructor<?>) member, alteration);
					} else
						XposedBridge.hookMethod(member, new XMethodHook(hook));
				} catch (NoSuchFieldError ex) {
					Util.log(hook, Log.WARN, ex.toString());
				} catch (Throwable ex) {
					mListHookError.add(ex.toString());
					Util.bug(hook, ex);
				}

			// Check if members found
			if (listMember.isEmpty() && !hook.getClassName().startsWith("com.google.android.gms")) {
				String message = "Method not found hook=" + hook;
				if (md == null || !md.isOptional())
					mListHookError.add(message);
				Util.log(hook, md != null && md.isOptional() ? Log.WARN : Log.ERROR, message);
			}
		} catch (Throwable ex) {
			mListHookError.add(ex.toString());
			Util.bug(hook, ex);
		}
	}

	// Helper classes

	private static class XMethodHook extends XC_MethodHook {
		private XHook mHook;

		public XMethodHook(XHook hook) {
			mHook = hook;
		}

		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			try {
				// Do not restrict Zygote
				if (Process.myUid() <= 0)
					return;

				// Pre processing
				XParam xparam = XParam.fromXposed(param);

				long start = System.currentTimeMillis();

				// Execute hook
				mHook.before(xparam);

				long ms = System.currentTimeMillis() - start;
				if (ms > PrivacyManager.cWarnHookDelayMs)
					Util.log(mHook, Log.WARN, String.format("%s %d ms", param.method.getName(), ms));

				// Post processing
				if (xparam.hasResult())
					param.setResult(xparam.getResult());
				if (xparam.hasThrowable())
					param.setThrowable(xparam.getThrowable());
				param.setObjectExtra("xextra", xparam.getExtras());
			} catch (Throwable ex) {
				Util.bug(null, ex);
			}
		}

		@Override
		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			if (!param.hasThrowable())
				try {
					// Do not restrict Zygote
					if (Process.myUid() <= 0)
						return;

					// Pre processing
					XParam xparam = XParam.fromXposed(param);
					xparam.setExtras(param.getObjectExtra("xextra"));

					long start = System.currentTimeMillis();

					// Execute hook
					mHook.after(xparam);

					long ms = System.currentTimeMillis() - start;
					if (ms > PrivacyManager.cWarnHookDelayMs)
						Util.log(mHook, Log.WARN, String.format("%s %d ms", param.method.getName(), ms));

					// Post processing
					if (xparam.hasResult())
						param.setResult(xparam.getResult());
					if (xparam.hasThrowable())
						param.setThrowable(xparam.getThrowable());
				} catch (Throwable ex) {
					Util.bug(null, ex);
				}
		}
	};

	private static class XMethodAlteration extends MS.MethodAlteration<Object, Object> {
		private XHook mHook;
		private Member mMember;

		public XMethodAlteration(XHook hook, Member member) {
			mHook = hook;
			mMember = member;
		}

		@Override
		public Object invoked(Object thiz, Object... args) throws Throwable {
			if (Process.myUid() <= 0)
				return invoke(thiz, args);

			XParam xparam = XParam.fromCydia(mMember, thiz, args);
			mHook.before(xparam);

			if (!xparam.hasResult() || xparam.hasThrowable()) {
				try {
					Object result = invoke(thiz, args);
					xparam.setResult(result);
				} catch (Throwable ex) {
					xparam.setThrowable(ex);
				}

				mHook.after(xparam);
			}

			if (xparam.hasThrowable())
				throw xparam.getThrowable();
			return xparam.getResult();
		}
	}
}
