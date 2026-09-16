#import "GooglePlus.h"

@implementation GooglePlus

// CDVPlugin registers this handler for Cordova's app and scene URL notifications.
- (void)handleOpenURL:(NSNotification*)notification
{
    NSURL* url = [notification object];

    NSString* possibleReversedClientId = [url.absoluteString componentsSeparatedByString:@":"].firstObject;

    if ([possibleReversedClientId isEqualToString:self.getreversedClientId] && self.isSigningIn) {
        self.isSigningIn = NO;
        [[GIDSignIn sharedInstance] handleURL:url];
    }
}

// If this returns false, you better not call the login function because of likely app rejection by Apple,
// see https://code.google.com/p/google-plus-platform/issues/detail?id=900
// Update: should be fine since we use the GoogleSignIn framework instead of the GooglePlus framework
- (void) isAvailable:(CDVInvokedUrlCommand*)command {
  CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:YES];
  [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) login:(CDVInvokedUrlCommand*)command {
  GIDSignIn *signIn = [self getGIDSignInObject:command];
  if (signIn == nil) {
    return;
  }
  NSDictionary *options = command.arguments[0];
  NSString *scopesString = options[@"scopes"];
  NSArray *scopes = scopesString.length > 0 ? [scopesString componentsSeparatedByString:@" "] : nil;
  self.isSigningIn = YES;
  [signIn signInWithPresentingViewController:self.viewController
                                      hint:options[@"loginHint"]
                          additionalScopes:scopes
                                completion:^(GIDSignInResult *result, NSError *error) {
    [self didSignInForUser:result.user serverAuthCode:result.serverAuthCode withError:error];
  }];
}

/** Get Google Sign-In object
 @date July 19, 2015
 */
- (void) trySilentLogin:(CDVInvokedUrlCommand*)command {
    [[self getGIDSignInObject:command] restorePreviousSignInWithCompletion:^(GIDGoogleUser *user, NSError *error) {
        [self didSignInForUser:user serverAuthCode:nil withError:error];
    }];
}

/** Get Google Sign-In object
 @date July 19, 2015
 @date updated March 15, 2015 (@author PointSource,LLC)
 */
- (GIDSignIn*) getGIDSignInObject:(CDVInvokedUrlCommand*)command {
    _callbackId = command.callbackId;
    NSDictionary* options = command.arguments[0];
    NSString *reversedClientId = [self getreversedClientId];

    if (reversedClientId == nil) {
        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Could not find REVERSED_CLIENT_ID url scheme in app .plist"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:_callbackId];
        return nil;
    }

    NSString *clientId = [self reverseUrlScheme:reversedClientId];

    NSString* serverClientId = options[@"webClientId"];
    BOOL offline = [options[@"offline"] boolValue];
    NSString* hostedDomain = options[@"hostedDomain"];


    GIDSignIn *signIn = [GIDSignIn sharedInstance];
    signIn.configuration = [[GIDConfiguration alloc] initWithClientID:clientId
                                                     serverClientID:offline ? serverClientId : nil
                                                       hostedDomain:hostedDomain
                                                        openIDRealm:nil];
    return signIn;
}

- (NSString*) reverseUrlScheme:(NSString*)scheme {
  NSArray* originalArray = [scheme componentsSeparatedByString:@"."];
  NSArray* reversedArray = [[originalArray reverseObjectEnumerator] allObjects];
  NSString* reversedString = [reversedArray componentsJoinedByString:@"."];
  return reversedString;
}

- (NSString*) getreversedClientId {
  NSArray* URLTypes = [[[NSBundle mainBundle] infoDictionary] objectForKey:@"CFBundleURLTypes"];

  if (URLTypes != nil) {
    for (NSDictionary* dict in URLTypes) {
      NSString *urlName = dict[@"CFBundleURLName"];
      if ([urlName isEqualToString:@"REVERSED_CLIENT_ID"]) {
        NSArray* URLSchemes = dict[@"CFBundleURLSchemes"];
        if (URLSchemes != nil) {
          return URLSchemes[0];
        }
      }
    }
  }
  return nil;
}

- (void) logout:(CDVInvokedUrlCommand*)command {
  [[GIDSignIn sharedInstance] signOut];
  CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"logged out"];
  [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void) disconnect:(CDVInvokedUrlCommand*)command {
  [[GIDSignIn sharedInstance] disconnectWithCompletion:^(NSError *error) {
    CDVPluginResult * pluginResult = error
      ? [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription]
      : [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"disconnected"];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
  }];
}

- (void) share_unused:(CDVInvokedUrlCommand*)command {
  // for a rainy day.. see for a (limited) example https://github.com/vleango/GooglePlus-PhoneGap-iOS/blob/master/src/ios/GPlus.m
}

#pragma mark - Google Sign-In result
/** Google Sign-In SDK
 @date July 19, 2015
 */
- (void)didSignInForUser:(GIDGoogleUser *)user serverAuthCode:(NSString *)serverAuthCode withError:(NSError *)error {
    self.isSigningIn = NO;
    if (error) {
        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.localizedDescription];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:_callbackId];
    } else {
        NSString *email = user.profile.email;
        NSString *idToken = user.idToken.tokenString;
        NSString *accessToken = user.accessToken.tokenString;
        NSString *refreshToken = user.refreshToken.tokenString;
        NSString *userId = user.userID;
        serverAuthCode = serverAuthCode != nil ? serverAuthCode : @"";
        NSURL *imageUrl = [user.profile imageURLWithDimension:120]; // TODO pass in img size as param, and try to sync with Android
        NSDictionary *result = @{
                       @"email"           : email,
                       @"idToken"         : idToken ? : [NSNull null],
                       @"serverAuthCode"  : serverAuthCode,
                       @"accessToken"     : accessToken,
                       @"refreshToken"    : refreshToken,
                       @"userId"          : userId,
                       @"displayName"     : user.profile.name       ? : [NSNull null],
                       @"givenName"       : user.profile.givenName  ? : [NSNull null],
                       @"familyName"      : user.profile.familyName ? : [NSNull null],
                       @"imageUrl"        : imageUrl ? imageUrl.absoluteString : [NSNull null],
                       };

        CDVPluginResult * pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:result];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:_callbackId];
    }
}

@end
