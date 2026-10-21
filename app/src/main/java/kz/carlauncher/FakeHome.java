package kz.carlauncher;

import android.app.Activity;
import android.os.Bundle;

/** Disabled dummy home screen, enabled for a moment only to make Android show the
 *  "choose default launcher" dialog on old versions. */
public class FakeHome extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        finish();
    }
}
