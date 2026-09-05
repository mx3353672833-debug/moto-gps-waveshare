#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@class MotoNavCoreSnapshot;

@interface MotoBLENavigationSnapshotInput : NSObject
@property(nonatomic, copy) NSString *stateName;
@property(nonatomic, copy) NSString *networkName;
@property(nonatomic, copy) NSString *displayPageName;
@property(nonatomic, copy) NSString *maneuverName;
@property(nonatomic, copy) NSString *trafficName;
@property(nonatomic) BOOL hasDestination;
@property(nonatomic) BOOL hasFix;
@property(nonatomic) BOOL gnssStale;
@property(nonatomic) BOOL offRoute;
@property(nonatomic) BOOL routeRequestInFlight;
@property(nonatomic) BOOL trafficRequestInFlight;
@property(nonatomic) BOOL hasRouteView;
@property(nonatomic) uint32_t routeToken;
@property(nonatomic) uint32_t routeGeneration;
@property(nonatomic) uint32_t maneuverID;
@property(nonatomic) uint32_t distanceToManeuverM;
@property(nonatomic) uint32_t remainingDistanceM;
@property(nonatomic) uint32_t remainingDurationS;
@property(nonatomic) uint32_t routeProgressM;
@property(nonatomic) uint32_t totalDistanceM;
@property(nonatomic) uint16_t speedDeciKPH;
@property(nonatomic) uint16_t speedLimitKPH;
@property(nonatomic) uint16_t headingCentiDegrees;
@property(nonatomic) uint16_t accuracyDecimeters;
@property(nonatomic) uint16_t crossTrackDecimeters;
@property(nonatomic) uint8_t roundaboutExit;
@property(nonatomic, copy) NSString *roadName;
@property(nonatomic, copy) NSString *instructionText;
@end

/// Phone-side projection of the active Apple Music player.  The Objective-C++
/// bridge owns the binary layout so Swift never duplicates the shared v1 wire
/// protocol.
@interface MotoBLEMediaStateInput : NSObject
@property(nonatomic) BOOL connected;
@property(nonatomic) BOOL playing;
@property(nonatomic) BOOL likeAvailable;
@property(nonatomic) BOOL liked;
@property(nonatomic) uint32_t trackToken;
@property(nonatomic) uint16_t positionSeconds;
@property(nonatomic) uint16_t durationSeconds;
@property(nonatomic, copy) NSString *sourceName;
@property(nonatomic, copy) NSString *trackTitle;
@property(nonatomic, copy) NSString *artistName;
@end

@interface MotoBLEMapPointInput : NSObject
@property(nonatomic) int32_t latitudeE6;
@property(nonatomic) int32_t longitudeE6;
@end

@interface MotoBLEMapRoadInput : NSObject
@property(nonatomic, copy) NSString *className;
@property(nonatomic, copy) NSArray<MotoBLEMapPointInput *> *points;
@end

@interface MotoBLEMapBuildingInput : NSObject
@property(nonatomic, copy) NSString *className;
@property(nonatomic, copy) NSArray<MotoBLEMapPointInput *> *points;
@end

@interface MotoBLEMapSceneInput : NSObject
@property(nonatomic) uint32_t sceneRevision;
@property(nonatomic) int32_t originLatitudeE6;
@property(nonatomic) int32_t originLongitudeE6;
@property(nonatomic) uint16_t radiusM;
@property(nonatomic, copy) NSArray<MotoBLEMapRoadInput *> *roads;
@property(nonatomic, copy) NSArray<MotoBLEMapBuildingInput *> *buildings;
@end

@interface MotoBLEDeviceCommand : NSObject
@property(nonatomic, readonly) uint16_t sequence;
@property(nonatomic, readonly) BOOL ackRequested;
@property(nonatomic, readonly) uint8_t kind;
@property(nonatomic, readonly) uint16_t commandID;
@property(nonatomic, readonly) uint8_t page;
@property(nonatomic, readonly) uint16_t x;
@property(nonatomic, readonly) uint16_t y;
@property(nonatomic, readonly) uint32_t eventTimeMs;
@end

@interface MotoBLEConnectionStatus : NSObject
@property(nonatomic, readonly) uint8_t role;
@property(nonatomic, readonly) uint8_t state;
@property(nonatomic, readonly) uint8_t minimumVersion;
@property(nonatomic, readonly) uint8_t maximumVersion;
@property(nonatomic, readonly) uint32_t capabilities;
@property(nonatomic, readonly) uint32_t sessionID;
@property(nonatomic, readonly) uint16_t maximumFrameSize;
@property(nonatomic, readonly) uint16_t heartbeatIntervalMs;
@end

@interface MotoBLEHeartbeat : NSObject
@property(nonatomic, readonly) uint32_t sessionID;
@end

/// One successfully decoded transport message, or one valid partial/duplicate
/// frame. Sequence and ACK metadata stay attached to the shared decoder output.
@interface MotoBLEInboundMessage : NSObject
@property(nonatomic, readonly) uint16_t sequence;
@property(nonatomic, readonly) BOOL ackRequested;
@property(nonatomic, readonly) BOOL complete;
@property(nonatomic, readonly) BOOL duplicate;
@property(nonatomic, readonly, nullable) MotoBLEConnectionStatus *connectionStatus;
@property(nonatomic, readonly, nullable) MotoBLEHeartbeat *heartbeat;
@property(nonatomic, readonly, nullable) MotoBLEDeviceCommand *deviceCommand;
@end

/// Thin Objective-C++ bridge over shared/ble_protocol. No wire constants or
/// binary payload layouts are reimplemented in Swift.
@interface MotoBLEProtocolCodec : NSObject

+ (NSString *)serviceUUIDString;
+ (NSString *)phoneToDeviceUUIDString;
+ (NSString *)deviceToPhoneUUIDString;

- (instancetype)initWithMaximumFrameSize:(NSUInteger)maximumFrameSize;

- (void)setMaximumFrameSize:(NSUInteger)maximumFrameSize;

/// Drops only device-to-phone fragment/sequence history. Outbound sequence
/// numbers are deliberately preserved so a new phone session is not mistaken
/// for a retransmission by the peripheral.
- (void)resetInboundState;

- (nullable NSArray<NSData *> *)encodePhoneStartingWithSessionID:(uint32_t)sessionID
                                                          error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodePhoneReadyWithSessionID:(uint32_t)sessionID
                                                        error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeHeartbeatWithSessionID:(uint32_t)sessionID
                                                  monotonicMs:(uint32_t)monotonicMs
                                                        error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeAckForSequence:(uint16_t)sequence
                                              status:(uint8_t)status
                                           commandID:(uint16_t)commandID
                                               error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeNavigationSnapshot:(MotoBLENavigationSnapshotInput *)snapshot
                                                    error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeRouteGeometryFromSnapshot:(MotoNavCoreSnapshot *)snapshot
                                                           error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeMediaState:(MotoBLEMediaStateInput *)state
                                           error:(NSError **)error;

- (nullable NSArray<NSData *> *)encodeMapScene:(MotoBLEMapSceneInput *)scene
                                          error:(NSError **)error;

/// Test hook for checking the shared MapScene payload without duplicating its
/// binary layout in Swift.
- (nullable NSData *)encodeMapScenePayloadForTesting:(MotoBLEMapSceneInput *)scene
                                                error:(NSError **)error;

/// Exposes the shared payload encoder only for the bundled golden-vector check.
- (nullable NSData *)encodeNavigationPayloadForGoldenCheck:(MotoBLENavigationSnapshotInput *)snapshot
                                                      error:(NSError **)error;

- (nullable MotoBLEInboundMessage *)pushDeviceFrame:(NSData *)frame
                                       receivedAtMs:(uint64_t)receivedAtMs
                                              error:(NSError **)error;

- (uint32_t)routeTokenForRouteID:(NSString *)routeID;

@end

NS_ASSUME_NONNULL_END
