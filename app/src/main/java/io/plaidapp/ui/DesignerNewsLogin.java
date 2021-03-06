/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.databinding.DataBindingUtil;
import android.databinding.ObservableBoolean;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.design.widget.TextInputLayout;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.Log;
import android.util.Patterns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.plaidapp.BuildConfig;
import io.plaidapp.R;
import io.plaidapp.data.api.designernews.model.AccessToken;
import io.plaidapp.data.api.designernews.model.User;
import io.plaidapp.data.prefs.DesignerNewsPrefs;
import io.plaidapp.databinding.ActivityDesignerNewsLoginBinding;
import io.plaidapp.ui.transitions.FabTransform;
import io.plaidapp.ui.transitions.MorphTransform;
import io.plaidapp.util.ScrimUtil;
import io.plaidapp.util.TransitionUtils;
import io.plaidapp.util.glide.CircleTransform;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class DesignerNewsLogin extends Activity {

    private static final int PERMISSIONS_REQUEST_GET_ACCOUNTS = 0;

    boolean isDismissing = false;
    ViewGroup container;
    TextView title;
    TextInputLayout usernameLabel;
    AutoCompleteTextView username;
    CheckBox permissionPrimer;
    TextInputLayout passwordLabel;
    EditText password;
    FrameLayout actionsContainer;
    Button signup;
    Button login;
    ProgressBar loading;
    DesignerNewsPrefs designerNewsPrefs;
    private boolean shouldPromptForPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityDesignerNewsLoginBinding binding = DataBindingUtil.setContentView(this, R.layout.activity_designer_news_login);
        binding.setHandlers(this);
        binding.setCredentials(new DesignerNewsCredentials());

        container = binding.container;
        title = binding.dialogTitle;
        usernameLabel = binding.usernameFloatLabel;
        username = binding.username;
        permissionPrimer = binding.permissionPrimer;
        passwordLabel = binding.passwordFloatLabel;
        password = binding.password;
        actionsContainer = binding.actionsContainer;
        signup = binding.signup;
        login = binding.login;
        loading = binding.included.loading;
        if (!FabTransform.setup(this, container)) {
            MorphTransform.setup(this, container,
                    ContextCompat.getColor(this, R.color.background_light),
                    getResources().getDimensionPixelSize(R.dimen.dialog_corners));
        }
        if (getWindow().getSharedElementEnterTransition() != null) {
            getWindow().getSharedElementEnterTransition().addListener(new TransitionUtils.TransitionListenerAdapter() {
                @Override
                public void onTransitionEnd(Transition transition) {
                    getWindow().getSharedElementEnterTransition().removeListener(this);
                    finishSetup();
                }
            });
        } else {
            finishSetup();
        }

        loading.setVisibility(View.GONE);
        setupAccountAutocomplete();
        designerNewsPrefs = DesignerNewsPrefs.get(this);
    }

    // the primer checkbox messes with focus order so force it
    public boolean onNameEditorAction(int actionId) {
        if (actionId == EditorInfo.IME_ACTION_NEXT) {
            password.requestFocus();
            return true;
        }
        return false;
    }

    public boolean onPasswordEditorAction(int actionId, DesignerNewsCredentials credentials) {
        if (actionId == EditorInfo.IME_ACTION_DONE && credentials.hasCredentials.get()) {
            login.performClick();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        dismiss(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_GET_ACCOUNTS) {
            TransitionManager.beginDelayedTransition(container);
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupAccountAutocomplete();
                username.requestFocus();
                username.showDropDown();
            } else {
                // if permission was denied check if we should ask again in the future (i.e. they
                // did not check 'never ask again')
                if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                    setupPermissionPrimer();
                } else {
                    // denied & shouldn't ask again. deal with it (•_•) ( •_•)>⌐■-■ (⌐■_■)
                    TransitionManager.beginDelayedTransition(container);
                    permissionPrimer.setVisibility(View.GONE);
                }
            }
        }
    }

    public void doLogin(DesignerNewsCredentials credentials) {
        showLoading();
        getAccessToken(credentials);
    }

    public void signup() {
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.designernews.co/users/new")));
    }

    public void dismiss(View view) {
        isDismissing = true;
        setResult(Activity.RESULT_CANCELED);
        finishAfterTransition();
    }

    /**
     * Postpone some of the setup steps so that we can run it after the enter transition
     * (if there is one). Otherwise we may show the permissions dialog or account dropdown
     * during the enter animation which is jarring.
     */
    void finishSetup() {
        if (shouldPromptForPermission) {
            requestPermissions(new String[]{ Manifest.permission.GET_ACCOUNTS },
                    PERMISSIONS_REQUEST_GET_ACCOUNTS);
            shouldPromptForPermission = false;
        }
        maybeShowAccounts();
    }

    public void onNameFocusChange() {
        maybeShowAccounts();
    }

    void maybeShowAccounts() {
        if (username.hasFocus()
                && username.isAttachedToWindow()
                && username.getAdapter() != null
                && username.getAdapter().getCount() > 0) {
            username.showDropDown();
        }
    }

    void showLoggedInUser() {
        final Call<User> authedUser = designerNewsPrefs.getApi().getAuthedUser();
        authedUser.enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (!response.isSuccessful()) return;
                final User user = response.body();
                designerNewsPrefs.setLoggedInUser(user);
                final Toast confirmLogin = new Toast(getApplicationContext());
                final View v = LayoutInflater.from(DesignerNewsLogin.this).inflate(R.layout
                        .toast_logged_in_confirmation, null, false);
                ((TextView) v.findViewById(R.id.name)).setText(user.display_name.toLowerCase());
                // need to use app context here as the activity will be destroyed shortly
                Glide.with(getApplicationContext())
                        .load(user.portrait_url)
                        .placeholder(R.drawable.avatar_placeholder)
                        .transform(new CircleTransform(getApplicationContext()))
                        .into((ImageView) v.findViewById(R.id.avatar));
                v.findViewById(R.id.scrim).setBackground(ScrimUtil
                        .makeCubicGradientScrimDrawable(
                                ContextCompat.getColor(DesignerNewsLogin.this, R.color.scrim),
                                5, Gravity.BOTTOM));
                confirmLogin.setView(v);
                confirmLogin.setGravity(Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0);
                confirmLogin.setDuration(Toast.LENGTH_LONG);
                confirmLogin.show();
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Log.e(getClass().getCanonicalName(), t.getMessage(), t);
            }
        });
    }

    void showLoginFailed() {
        Snackbar.make(container, R.string.login_failed, Snackbar.LENGTH_SHORT).show();
        showLogin();
        password.requestFocus();
    }

    private void showLoading() {
        TransitionManager.beginDelayedTransition(container);
        title.setVisibility(View.GONE);
        usernameLabel.setVisibility(View.GONE);
        permissionPrimer.setVisibility(View.GONE);
        passwordLabel.setVisibility(View.GONE);
        actionsContainer.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
    }

    private void showLogin() {
        TransitionManager.beginDelayedTransition(container);
        title.setVisibility(View.VISIBLE);
        usernameLabel.setVisibility(View.VISIBLE);
        passwordLabel.setVisibility(View.VISIBLE);
        actionsContainer.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
    }

    private void getAccessToken(DesignerNewsCredentials credentials) {
        final Call<AccessToken> login = designerNewsPrefs.getApi().login(
                buildLoginParams(credentials.username, credentials.password));
        login.enqueue(new Callback<AccessToken>() {
            @Override
            public void onResponse(Call<AccessToken> call, Response<AccessToken> response) {
                if (response.isSuccessful()) {
                    designerNewsPrefs.setAccessToken(DesignerNewsLogin.this, response.body().access_token);
                    showLoggedInUser();
                    setResult(Activity.RESULT_OK);
                    finish();
                } else {
                    showLoginFailed();
                }
            }

            @Override
            public void onFailure(Call<AccessToken> call, Throwable t) {
                Log.e(getClass().getCanonicalName(), t.getMessage(), t);
                showLoginFailed();
            }
        });
    }

    private Map<String, String> buildLoginParams(@NonNull String username, @NonNull String password) {
        final Map<String, String> loginParams = new HashMap<>(5);
        loginParams.put("client_id", BuildConfig.DESIGNER_NEWS_CLIENT_ID);
        loginParams.put("client_secret", BuildConfig.DESIGNER_NEWS_CLIENT_SECRET);
        loginParams.put("grant_type", "password");
        loginParams.put("username", username);
        loginParams.put("password", password);
        return loginParams;
    }

    private void setupAccountAutocomplete() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.GET_ACCOUNTS) ==
                PackageManager.PERMISSION_GRANTED) {
            permissionPrimer.setVisibility(View.GONE);
            final Account[] accounts = AccountManager.get(this).getAccounts();
            final Set<String> emailSet = new HashSet<>();
            for (Account account : accounts) {
                if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                    emailSet.add(account.name);
                }
            }
            username.setAdapter(new ArrayAdapter<>(this,
                    R.layout.account_dropdown_item, new ArrayList<>(emailSet)));
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.GET_ACCOUNTS)) {
                setupPermissionPrimer();
            } else {
                permissionPrimer.setVisibility(View.GONE);
                shouldPromptForPermission = true;
            }
        }
    }

    private void setupPermissionPrimer() {
        permissionPrimer.setChecked(false);
        permissionPrimer.setVisibility(View.VISIBLE);
        permissionPrimer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    requestPermissions(new String[]{ Manifest.permission.GET_ACCOUNTS },
                            PERMISSIONS_REQUEST_GET_ACCOUNTS);
                }
            }
        });
    }

    public static class DesignerNewsCredentials {
        private String username;
        private String password;

        public final ObservableBoolean hasCredentials = new ObservableBoolean();

        public DesignerNewsCredentials() {
        }

        public String getPassword() {
            return password;
        }

        private boolean hasUsernameAndPassword() {
            return !TextUtils.isEmpty(username) && !TextUtils.isEmpty(password);
        }

        public void setPassword(String password) {
            this.password = password;
            hasCredentials.set(hasUsernameAndPassword());
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
            hasCredentials.set(hasUsernameAndPassword());
        }
    }
}
