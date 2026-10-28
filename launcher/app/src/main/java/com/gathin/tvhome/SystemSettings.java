package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/** The first vendor settings entry from settings_fallback_components that is installed
 *  (Xiaomi China, TCL / FFALCON, Fire OS), falling back to the standard settings intent. */
final class SystemSettings {
    private SystemSettings() {}

    static Intent intent(Context context, String action) {
        PackageManager pm = context.getPackageManager();

        for (String value : context.getResources().getStringArray(R.array.settings_fallback_components)) {
            ComponentName component = ComponentName.unflattenFromString(value);
            if (component == null) continue;
            // Explicit resolveActivity() always succeeds, so check the package exists instead.
            try {
                pm.getPackageInfo(component.getPackageName(), 0);
                return new Intent().setComponent(component);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        Intent standard = new Intent(action);
        ResolveInfo resolved = pm.resolveActivity(standard, PackageManager.MATCH_DEFAULT_ONLY);
        // Fire OS resolves the standard actions to a CTS compliance stub that opens nothing;
        // treat it as missing so the vendor fallback runs.
        if (resolved != null && !resolved.activityInfo.name.contains("CTSDummy")) return standard;

        return standard;
    }
}
