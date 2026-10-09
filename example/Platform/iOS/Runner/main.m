#import <UIKit/UIKit.h>
#import <QuartzCore/QuartzCore.h>
#include <stdint.h>

extern void dotnative_register_plugins(void) __attribute__((weak_import));
extern int32_t dotnative_ios_initialize(void *host);
extern int32_t dotnative_app_start(float width, float height);
extern int32_t dotnative_app_resize(float width, float height);
extern int32_t dotnative_app_tick(void);
extern int32_t dotnative_app_accessibility(int32_t motion,float scale,int32_t contrast);
extern int32_t dotnative_app_stop(void);
extern int32_t dotnative_app_activity(int32_t activity);
extern int32_t dotnative_app_system_theme(int32_t theme);
static void RequireSuccess(int32_t status) { if (status != 0) abort(); }
static void SyncAccessibility(void) {
    static int previousMotion=-1,previousContrast=-1;static float previousScale=-1;
    int motion=UIAccessibilityIsReduceMotionEnabled(),contrast=UIAccessibilityDarkerSystemColorsEnabled(); float scale=fminf(5,fmaxf(.5,[UIFont preferredFontForTextStyle:UIFontTextStyleBody].pointSize/17.0));
    if(motion!=previousMotion || scale!=previousScale || contrast!=previousContrast) {RequireSuccess(dotnative_app_accessibility(motion,scale,contrast));previousMotion=motion;previousScale=scale;previousContrast=contrast;}
}
static void SyncSystemTheme(void) {
    SyncAccessibility();
    static int32_t previous = 0;
    int32_t theme = UIScreen.mainScreen.traitCollection.userInterfaceStyle == UIUserInterfaceStyleDark ? 2 : 1;
    if (theme != previous) { RequireSuccess(dotnative_app_system_theme(theme)); previous = theme; }
}

@interface DotNativeController : UIViewController
@property(nonatomic, strong) UIView *mount;
@property(nonatomic, strong) CADisplayLink *displayLink;
@property(nonatomic) BOOL started;
@property(nonatomic) BOOL stopping;
@end
@implementation DotNativeController
- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = UIColor.whiteColor;
    self.mount = [[UIView alloc] initWithFrame:CGRectZero];
    [self.view addSubview:self.mount];
}
- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    if (self.stopping) return;
    CGRect frame = self.view.safeAreaLayoutGuide.layoutFrame;
    // Native tab bars own the bottom safe area. The engine reserves it for screens without a bar.
    frame.size.height = CGRectGetMaxY(self.view.bounds) - frame.origin.y;
    self.mount.frame = frame;
    CGSize size = self.mount.bounds.size;
    if (size.width <= 0 || size.height <= 0) return;
    if (!self.started) {
        RequireSuccess(dotnative_ios_initialize((__bridge void *)self.mount));
        if (dotnative_register_plugins) dotnative_register_plugins();
        SyncSystemTheme();
        RequireSuccess(dotnative_app_start((float)size.width, (float)size.height));
        self.started = YES;
        UIApplicationState state = UIApplication.sharedApplication.applicationState;
        RequireSuccess(dotnative_app_activity(state == UIApplicationStateActive ? 0 : (state == UIApplicationStateBackground ? 2 : 1)));
        self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(tick:)];
        [self.displayLink addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
        NSLog(@"DotNative: C# startup accepted");
    } else {
        RequireSuccess(dotnative_app_resize((float)size.width, (float)size.height));
    }
}
- (void)tick:(CADisplayLink *)sender { SyncSystemTheme(); RequireSuccess(dotnative_app_tick()); }
- (void)stop {
    [self.displayLink invalidate]; self.displayLink = nil;
    if (self.started) { self.stopping = YES; self.started = NO; [self pollStop]; }
}
- (void)pollStop {
    int32_t status = dotnative_app_stop();
    if (status == 1) {
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_MSEC), dispatch_get_main_queue(), ^{ [self pollStop]; });
    } else { RequireSuccess(status); }
}
@end

@interface AppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation AppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [DotNativeController new];
    [self.window makeKeyAndVisible];
    return YES;
}
- (void)applicationDidBecomeActive:(UIApplication *)application { RequireSuccess(dotnative_app_activity(0)); }
- (void)applicationWillResignActive:(UIApplication *)application { RequireSuccess(dotnative_app_activity(1)); }
- (void)applicationDidEnterBackground:(UIApplication *)application { RequireSuccess(dotnative_app_activity(2)); }
- (void)applicationWillEnterForeground:(UIApplication *)application { RequireSuccess(dotnative_app_activity(1)); }
- (void)applicationWillTerminate:(UIApplication *)application {
    [(DotNativeController *)self.window.rootViewController stop];
}
@end
int main(int argc, char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc, argv, nil, NSStringFromClass(AppDelegate.class)); }
}
