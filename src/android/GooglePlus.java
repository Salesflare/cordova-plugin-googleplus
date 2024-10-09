package nl.xservices.plugins;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.os.CancellationSignal;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.common.api.ApiException;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.credentials.Credential;
import androidx.credentials.CustomCredential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.ClearCredentialException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialCancellationException;

import org.apache.cordova.*;
import org.apache.cordova.engine.SystemWebChromeClient;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import android.content.pm.Signature;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Originally written by Eddy Verbruggen (http://github.com/EddyVerbruggen/cordova-plugin-googleplus)
 * Forked/Duplicated and Modified by PointSource, LLC, 2016.
 */
public class GooglePlus extends CordovaPlugin {

    public static final String ACTION_IS_AVAILABLE = "isAvailable";
    public static final String ACTION_LOGIN = "login";
    public static final String ACTION_TRY_SILENT_LOGIN = "trySilentLogin";
    public static final String ACTION_LOGOUT = "logout";
    public static final String ACTION_DISCONNECT = "disconnect";
    public static final String ACTION_GET_SIGNING_CERTIFICATE_FINGERPRINT = "getSigningCertificateFingerprint";

    private final static String FIELD_ACCESS_TOKEN      = "accessToken";
    private final static String FIELD_TOKEN_EXPIRES     = "expires";
    private final static String FIELD_TOKEN_EXPIRES_IN  = "expires_in";
    private final static String VERIFY_TOKEN_URL        = "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=";

    //String options/config object names passed in to login and trySilentLogin
    public static final String ARGUMENT_WEB_CLIENT_ID = "webClientId";
    public static final String ARGUMENT_SCOPES = "scopes";
    public static final String ARGUMENT_OFFLINE_KEY = "offline";
    public static final String ARGUMENT_HOSTED_DOMAIN = "hostedDomain";

    public static final String TAG = "GooglePlugin";
    public static final int RC_GOOGLEPLUS = 1552; // Request Code to identify our plugin's activities
    public static final int RC_CANCELATION = 12501; // Response code for when the user cancels the sign in request
    public static final int KAssumeStaleTokenSec = 60;

    // Wraps our service connection to Google Play services and provides access to the users sign in state and Google APIs
    // private GoogleApiClient mGoogleApiClient;
    private CallbackContext savedCallbackContext;

    private String scopes;
    private String webClientId;
    private JSONObject activityReturnObject;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
    }

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        this.savedCallbackContext = callbackContext;

        if (ACTION_IS_AVAILABLE.equals(action)) {
            final boolean avail = true;
            savedCallbackContext.success("" + avail);

        } else if (ACTION_LOGIN.equals(action)) {
            //pass args into the sign in request
            startSignIn(args.optJSONObject(0));

            // Tries to Log the user in
            Log.i(TAG, "Trying to Log in!");
            cordova.setActivityResultCallback(this); //sets this class instance to be an activity result listener

        } else if (ACTION_LOGOUT.equals(action)) {
            Log.i(TAG, "Trying to logout!");
            signOut();

        } else {
            Log.i(TAG, "This action doesn't exist");
            return false;

        }
        return true;
    }

    /**
     * Create a credential manager object and request credentials
     * @param clientOptions - the options object passed in the login function
     */
    private synchronized void startSignIn(JSONObject clientOptions) throws JSONException {
        if (clientOptions == null) {
            return;
        }

        // Try to get web client id
        this.webClientId = clientOptions.optString(ARGUMENT_WEB_CLIENT_ID, null);

        this.scopes = clientOptions.optString(ARGUMENT_SCOPES, null);

        GetSignInWithGoogleOption signInWithGoogleOption = new GetSignInWithGoogleOption.Builder(this.webClientId)
            .build();

        // Build the credential request with the web client id
        GetCredentialRequest request = new GetCredentialRequest.Builder()
            .addCredentialOption(signInWithGoogleOption)
            .build();

        // Create a CredentialManager object with the current context
        CredentialManager credentialManager = CredentialManager.create(webView.getContext());

        // Create an Executor, used for async calls
        Executor executor = Executors.newSingleThreadExecutor();
        // Create a CancellationSignal, currently not used but can be activated to cancel the request
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Define the callback
        CredentialManagerCallback<GetCredentialResponse, GetCredentialException> callback = new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
            @Override
            public void onResult(@NonNull GetCredentialResponse result) {

                handleSignIn(result);               
            }

            @Override
            public void onError(@NonNull GetCredentialException e) {
                // Handle the error
                if (e instanceof GetCredentialCancellationException) {
                    // Handle the specific case where the exception is of type GetCredentialCancellationException
                    Log.e(TAG, "Credential retrieval was cancelled: " + e.getMessage());
                    savedCallbackContext.error(RC_CANCELATION);
                } else {
                    // Handle other types of GetCredentialException
                    Log.e(TAG, "Credential retrieval failed: " + e.getMessage());
                    savedCallbackContext.error("Credential retrieval failed: " + e.getMessage());
                }
            }
        };

        // Start the sign in request
        credentialManager.getCredentialAsync(
            webView.getContext(),
            request,
            cancellationSignal,
            executor,
            callback
        );

        Log.i(TAG, "GoogleApiClient built");
    }

    /**
     * Handle the result of the sign in request and start the authorization request to retrieve the serverAuthCode
     * @param result - the result of the sign in request
     */
    private void handleSignIn(GetCredentialResponse result) {

        // Handle the retrieved credential
        Log.i(TAG, "Credential retrieved successfully");

        Credential credential = result.getCredential();

        if (credential instanceof CustomCredential) {
            if (GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
                GoogleIdTokenCredential googleIdTokenCredential = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
                String token = googleIdTokenCredential.getIdToken();

                String[] scopesArray = scopes.split(" ");
                List<Scope> requestedScopes = Arrays.stream(scopesArray)
                                                    .map(Scope::new)
                                                    .collect(Collectors.toList());

                AuthorizationRequest authorizationRequest = 
                    AuthorizationRequest.builder()
                        .setRequestedScopes(requestedScopes)
                        .requestOfflineAccess(this.webClientId)
                        .build();
                        
                Identity.getAuthorizationClient(webView.getContext())
                        .authorize(authorizationRequest)
                        .addOnSuccessListener(
                            authorizationResult -> {

                            // Prepare the return object with the data that's available from the credential response
                            JSONObject returnObject = new JSONObject();
                            try {
                                returnObject.put("email", googleIdTokenCredential.getId());
                                returnObject.put("idToken", googleIdTokenCredential.getIdToken());
                                returnObject.put("serverAuthCode", authorizationResult.getServerAuthCode());
                                returnObject.put("userId", googleIdTokenCredential.getId());
                                returnObject.put("displayName", googleIdTokenCredential.getDisplayName());
                                returnObject.put("familyName", googleIdTokenCredential.getFamilyName());
                                returnObject.put("givenName", googleIdTokenCredential.getGivenName());
                                returnObject.put("imageUrl", googleIdTokenCredential.getProfilePictureUri());
                            } catch (Exception e) {
                                savedCallbackContext.error("Trouble obtaining result, error: " + e.getMessage());
                            }

                            if (authorizationResult.hasResolution()) {
                                // Store the return object to be used later in the authorization callback
                                activityReturnObject = returnObject;
                                // Access needs to be granted by the user
                                PendingIntent pendingIntent = authorizationResult.getPendingIntent();
                                try {
                                    // Start the intent to prompt the user
                                    cordova.getActivity().startIntentSenderForResult(
                                        pendingIntent.getIntentSender(),
                                        RC_GOOGLEPLUS,
                                        null,
                                        0,
                                        0,
                                        0
                                    );
                                } catch (IntentSender.SendIntentException e) {
                                    Log.e(TAG, "Couldn't start Authorization UI: " + e.getLocalizedMessage());
                                    savedCallbackContext.error("Couldn't start Authorization UI");
                                }
                            } else {
                                // Access already granted, continue with user action
                                Log.i(TAG, "Already authorized: ");
                                savedCallbackContext.success(returnObject);
                            }
                            })
                        .addOnFailureListener(e -> savedCallbackContext.error("Couldn't complete Authorization"));

                
            }
        }
        else {
            Log.e(TAG, "Credential is not a GoogleIdTokenCredential");
            savedCallbackContext.error("Credential is not a GoogleIdTokenCredential");
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_GOOGLEPLUS) {
            if (resultCode == Activity.RESULT_OK) {
                // Handle successful authorization
                Log.i(TAG, "Authorization successful");

                // Retrieve the serverAuthCode
                try {
                    AuthorizationResult authorizationResult = Identity.getAuthorizationClient(webView.getContext()).getAuthorizationResultFromIntent(data);
                    String serverAuthCode = authorizationResult.getServerAuthCode();

                    try {
                        activityReturnObject.put("serverAuthCode", serverAuthCode); // Add the serverAuthCode to the result
                        savedCallbackContext.success(activityReturnObject);
                    } catch (JSONException e) {
                        savedCallbackContext.error("Trouble adding serverAuthCode, error: " + e.getMessage());
                    }

                } catch (ApiException e) {
                    Log.e(TAG, "Couldn't get serverAuthCode: " + e.getLocalizedMessage());
                    savedCallbackContext.error("Couldn't get serverAuthCode");
                }
                
            } else {
                // Handle authorization failure
                Log.e(TAG, "Authorization failed");
                savedCallbackContext.error(RC_CANCELATION);
            }
        }
    }

    /**
     * Signs the user out from the client
     */
    private void signOut() {

        // String type = ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE;
        ClearCredentialStateRequest request = new ClearCredentialStateRequest();
        CredentialManager credentialManager = CredentialManager.create(webView.getContext());

        // Create an Executor
        Executor executor = Executors.newSingleThreadExecutor();
        // Create a CancellationSignal
        CancellationSignal cancellationSignal = new CancellationSignal();

        // Define the callback
        CredentialManagerCallback<Void, ClearCredentialException> callback = new CredentialManagerCallback<Void, ClearCredentialException>() {
            @Override
            public void onResult(Void result) {

                savedCallbackContext.success("Logged user out");               
            }

            @Override
            public void onError(@NonNull ClearCredentialException e) {
                // Handle the error
                Log.e(TAG, "Credential clear failed: " + e.getMessage());
                savedCallbackContext.error(e.getMessage());
            }
        };

        credentialManager.clearCredentialStateAsync(
            request,
            cancellationSignal,
            executor,
            callback
        );
    }
}
