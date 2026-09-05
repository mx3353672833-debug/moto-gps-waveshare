#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface MotoNavPointValue : NSObject
@property(nonatomic) double longitudeDeg;
@property(nonatomic) double latitudeDeg;
@end

@interface MotoNavManeuverValue : NSObject
@property(nonatomic) uint32_t identifier;
@property(nonatomic, copy) NSString *typeName;
@property(nonatomic) double routeOffsetM;
@property(nonatomic, copy) NSString *roadName;
@property(nonatomic, copy) NSString *instructionText;
@property(nonatomic) uint8_t roundaboutExit;
@end

@interface MotoNavTrafficValue : NSObject
@property(nonatomic) double startOffsetM;
@property(nonatomic) double endOffsetM;
@property(nonatomic, copy) NSString *levelName;
@end

@interface MotoNavRouteValue : NSObject
@property(nonatomic, copy) NSString *routeID;
@property(nonatomic, copy) NSArray<MotoNavPointValue *> *polyline;
@property(nonatomic, copy) NSArray<MotoNavManeuverValue *> *maneuvers;
@property(nonatomic, copy) NSArray<MotoNavTrafficValue *> *traffic;
@property(nonatomic) double totalDistanceM;
@property(nonatomic) uint32_t totalDurationS;
@property(nonatomic) uint16_t speedLimitKPH;
@property(nonatomic) uint64_t generatedAtMs;
@end

@interface MotoNavCoreCommand : NSObject
@property(nonatomic, readonly, copy) NSString *typeName;
@property(nonatomic, readonly) uint32_t requestID;
@property(nonatomic, readonly) double originLongitudeDeg;
@property(nonatomic, readonly) double originLatitudeDeg;
@property(nonatomic, readonly) double destinationLongitudeDeg;
@property(nonatomic, readonly) double destinationLatitudeDeg;
@property(nonatomic, readonly) BOOL reroute;
@property(nonatomic, readonly, copy) NSString *routeID;
@end

@interface MotoNavCoreSnapshot : NSObject
@property(nonatomic, readonly, copy) NSString *stateName;
@property(nonatomic, readonly, copy) NSString *networkName;
@property(nonatomic, readonly, copy) NSString *displayPageName;
@property(nonatomic, readonly) BOOL hasDestination;
@property(nonatomic, readonly) BOOL hasUsableFix;
@property(nonatomic, readonly) BOOL gnssStale;
@property(nonatomic, readonly) BOOL offRoute;
@property(nonatomic, readonly) BOOL routeRequestInFlight;
@property(nonatomic, readonly) BOOL trafficRequestInFlight;
@property(nonatomic, readonly) double speedMPS;
@property(nonatomic, readonly) double headingDeg;
@property(nonatomic, readonly) double horizontalAccuracyM;
@property(nonatomic, readonly) double crossTrackDistanceM;
@property(nonatomic, readonly) uint16_t speedLimitKPH;
@property(nonatomic, readonly) BOOL hasRouteView;
@property(nonatomic, readonly) double routeViewOriginLongitudeDeg;
@property(nonatomic, readonly) double routeViewOriginLatitudeDeg;
@property(nonatomic, readonly, copy) NSArray<MotoNavPointValue *> *routeViewPoints;
@property(nonatomic, readonly, copy) NSString *routeID;
@property(nonatomic, readonly) double routeProgressM;
@property(nonatomic, readonly) double totalDistanceM;
@property(nonatomic, readonly) double remainingDistanceM;
@property(nonatomic, readonly) uint32_t remainingDurationS;
@property(nonatomic, readonly) BOOL hasNextManeuver;
@property(nonatomic, readonly) uint32_t maneuverID;
@property(nonatomic, readonly, copy) NSString *maneuverTypeName;
@property(nonatomic, readonly) double distanceToManeuverM;
@property(nonatomic, readonly, copy) NSString *roadName;
@property(nonatomic, readonly, copy) NSString *instructionText;
@property(nonatomic, readonly) uint8_t roundaboutExit;
@property(nonatomic, readonly, copy) NSString *trafficName;
@property(nonatomic, readonly) uint64_t nowMs;
@property(nonatomic, readonly) uint32_t routeGeneration;
@end

/// Owns the real shared NavApp/NavCore. Swift executes returned network
/// commands, but must not reproduce route matching, rerouting or progression.
@interface MotoNavCoreBridge : NSObject

@property(nonatomic, readonly) MotoNavCoreSnapshot *snapshot;

- (NSArray<MotoNavCoreCommand *> *)reset;
- (NSArray<MotoNavCoreCommand *> *)beginNavigationToLongitude:(double)longitudeDeg
                                                      latitude:(double)latitudeDeg;
- (NSArray<MotoNavCoreCommand *> *)cancelNavigation;
- (NSArray<MotoNavCoreCommand *> *)setNetworkStateName:(NSString *)stateName;
- (NSArray<MotoNavCoreCommand *> *)selectDisplayPageName:(NSString *)pageName;
- (NSArray<MotoNavCoreCommand *> *)pushFixLongitude:(double)longitudeDeg
                                           latitude:(double)latitudeDeg
                                          accuracyM:(double)accuracyM
                                            speedMPS:(double)speedMPS
                                          headingDeg:(double)headingDeg
                                         timestampMs:(uint64_t)timestampMs;
- (NSArray<MotoNavCoreCommand *> *)acceptRoute:(MotoNavRouteValue *)route
                                     requestID:(uint32_t)requestID
                                   receivedAtMs:(uint64_t)receivedAtMs;
- (NSArray<MotoNavCoreCommand *> *)rejectRouteRequestID:(uint32_t)requestID
                                               retryable:(BOOL)retryable
                                             receivedAtMs:(uint64_t)receivedAtMs;
- (NSArray<MotoNavCoreCommand *> *)acceptTrafficForRouteID:(NSString *)routeID
                                                  segments:(NSArray<MotoNavTrafficValue *> *)segments
                                        remainingDurationS:(uint32_t)remainingDurationS
                                                 requestID:(uint32_t)requestID
                                               observedAtMs:(uint64_t)observedAtMs;
- (NSArray<MotoNavCoreCommand *> *)rejectTrafficRequestID:(uint32_t)requestID
                                              receivedAtMs:(uint64_t)receivedAtMs;
- (NSArray<MotoNavCoreCommand *> *)tickAtMs:(uint64_t)timestampMs;

@end

NS_ASSUME_NONNULL_END
