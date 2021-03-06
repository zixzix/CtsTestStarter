package g4rb4g3.at.ctsteststarter;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.widget.Toast;

import com.lge.ivi.IKeyInterceptor;
import com.lge.ivi.IKeyService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import androidx.core.app.NotificationCompat;

public class KeyInterceptorService extends Service {
  public static final String PREFERENCES_NAME = "preferences";

  public static final int SHOW_MESSAGE = 1;
  public static final int MAPPED_APP = 2;
  public static final int UNMAPPED_APP = 3;


  private final IBinder mBinder = new KeyInterceptorBinder();
  private List<Handler> mRegisteredHandlers = new ArrayList<>();
  private SharedPreferences mSharedPreferences;
  private ApplicationInfo mNextAppMappingApplicationInfo;
  private boolean mClearKeyMapping = false;
  private boolean mActivityVisible = false;
  private boolean mMapBackKey = false;
  private boolean mMapRecentApps = false;
  private Context mContext;

  private IKeyInterceptor.Stub mKeyInterceptor = new IKeyInterceptor.Stub() {
    @Override
    public boolean onKeyEvent(KeyEvent keyEvent) throws RemoteException {
      if (keyEvent.getAction() != KeyEvent.ACTION_DOWN || !keyEvent.isLongPress()) {
        return false;
      }

      if (keyEvent.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
        if (!mActivityVisible) {
          startActivity(new Intent(mContext, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (mMapBackKey || mClearKeyMapping || mNextAppMappingApplicationInfo != null) {
          notifyHandlers(SHOW_MESSAGE, getString(R.string.settings_cannot_be_mapped));
          cancel();
        }
        return true;
      }

      if (mNextAppMappingApplicationInfo != null) {
        mSharedPreferences.edit().putString(String.valueOf(keyEvent.getKeyCode()), mNextAppMappingApplicationInfo.packageName).commit();
        notifyHandlers(SHOW_MESSAGE, getString(R.string.mapping_app_completed, keyEvent.getKeyCode(), mNextAppMappingApplicationInfo.name));
        notifyHandlers(MAPPED_APP, mNextAppMappingApplicationInfo.packageName);
        mNextAppMappingApplicationInfo = null;
        return true;
      }

      if (mClearKeyMapping) {
        String packageName = mSharedPreferences.getString(String.valueOf(keyEvent.getKeyCode()), null);
        if (packageName != null) {
          notifyHandlers(UNMAPPED_APP, packageName);
          mSharedPreferences.edit().remove(String.valueOf(keyEvent.getKeyCode())).commit();
          notifyHandlers(SHOW_MESSAGE, getString(R.string.mapping_cleared, keyEvent.getKeyCode()));
        }
        mClearKeyMapping = false;
        return true;
      }

      if (mMapBackKey) {
        mSharedPreferences.edit().putString(String.valueOf(keyEvent.getKeyCode()), "back_key").commit();
        notifyHandlers(SHOW_MESSAGE, getString(R.string.mapping_back_completed, keyEvent.getKeyCode()));
        mMapBackKey = false;
        return true;
      }

      if (mMapRecentApps) {
        mSharedPreferences.edit().putString(String.valueOf(keyEvent.getKeyCode()), "recent_apps").commit();
        notifyHandlers(SHOW_MESSAGE, getString(R.string.mapping_recent_completed, keyEvent.getKeyCode()));
        mMapRecentApps = false;
        return true;
      }

      String packageName = mSharedPreferences.getString(String.valueOf(keyEvent.getKeyCode()), null);
      if (packageName == null) {
        return false;
      }
      switch (packageName) {
        case "back_key":
          injectKeyEvent(KeyEvent.KEYCODE_BACK);
          return true;
        case "recent_apps":
          openRecentApps();
          return true;
        default:
          mContext.startActivity(mContext.getPackageManager().getLaunchIntentForPackage(packageName));
          return true;
      }
    }
  };

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  @Override
  public void onCreate() {
    Notification notification = new NotificationCompat.Builder(this, null)
        .setContentTitle(getString(R.string.app_name))
        .setSmallIcon(R.mipmap.ic_launcher)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .build();
    startForeground(1, notification);

    mContext = getApplicationContext();
    mSharedPreferences = mContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);

    try {
      IKeyService keyService = getKeyService();
      keyService.setKeyInterceptor(mKeyInterceptor);
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  private IKeyService getKeyService() {
    try {
      Method method = Class.forName("android.os.ServiceManager").getMethod("getService", String.class);
      IBinder binder = (IBinder) method.invoke(null, "com.lge.ivi.server.Key");
      if (binder != null) {
        return IKeyService.Stub.asInterface(binder);
      }
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    } catch (InvocationTargetException e) {
      e.printStackTrace();
    }
    return null;
  }

  public void registerHandler(Handler handler) {
    mRegisteredHandlers.add(handler);
  }

  public void unregisterHandler(Handler handler) {
    mRegisteredHandlers.remove(handler);
  }

  public void mapAppToKey(ApplicationInfo applicationInfo) {
    mNextAppMappingApplicationInfo = applicationInfo;
  }

  public void mapBackKey() {
    mMapBackKey = true;
  }

  public void clearKeyMapping() {
    mClearKeyMapping = true;
  }

  public void mapRecentApps() {
    mMapRecentApps = true;
  }

  public void cancel() {
    mNextAppMappingApplicationInfo = null;
    mMapBackKey = false;
    mClearKeyMapping = false;
    mMapRecentApps = false;
  }

  public void isActivityVisible(boolean visible) {
    mActivityVisible = visible;
  }

  private void notifyHandlers(int what, Object obj) {
    Message msg = new Message();
    msg.what = what;
    msg.obj = obj;
    for (Handler handler : mRegisteredHandlers) {
      handler.sendMessage(msg);
    }
  }

  private void injectKeyEvent(int keyCode) {
    try {
      String keyCommand = "input keyevent " + keyCode + " > /dev/null 2> /dev/null < /dev/null &";
      ProcessExecutor.executeRootCommand(keyCommand);
    } catch (RemoteException e) {
      e.printStackTrace();
    }
  }

  private void openRecentApps() {
    try {
      Class serviceManagerClass = Class.forName("android.os.ServiceManager");
      Method getService = serviceManagerClass.getMethod("getService", String.class);
      IBinder retbinder = (IBinder) getService.invoke(null, "statusbar");
      Class statusBarClass = Class.forName(retbinder.getInterfaceDescriptor());
      Object statusBarObject = statusBarClass.getClasses()[0]
          .getMethod("asInterface", IBinder.class).invoke(null, retbinder);
      Method toggleRecentApps = statusBarClass.getMethod("toggleRecentApps");
      toggleRecentApps.setAccessible(true);
      toggleRecentApps.invoke(statusBarObject);
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  public class KeyInterceptorBinder extends Binder {
    public KeyInterceptorService getService() {
      return KeyInterceptorService.this;
    }
  }
}
