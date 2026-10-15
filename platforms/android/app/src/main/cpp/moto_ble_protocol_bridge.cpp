#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <vector>
#include <string>
#include <memory>
#include "moto/ble_protocol/ble_protocol.hpp"

#define LOG_TAG "MotoBLEProtocolBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace moto::ble;

namespace {

struct CodecStorage {
    std::size_t max_frame_size;
    SequenceGenerator sequence;
    std::uint16_t last_encoded_sequence = 0;
    Reassembler reassembler;

    explicit CodecStorage(std::size_t frame_size)
        : max_frame_size(std::clamp<std::size_t>(
              frame_size, kFrameOverhead + 1, kMaxBleAttributeValueSize)),
          reassembler(ReassemblerConfig{}) {}
};

jobjectArray CreateByteArrayArray(JNIEnv *env, const std::vector<Bytes>& frames) {
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray result = env->NewObjectArray(frames.size(), byteArrayClass, nullptr);
    for (size_t i = 0; i < frames.size(); ++i) {
        jbyteArray byteArray = env->NewByteArray(frames[i].size());
        env->SetByteArrayRegion(byteArray, 0, frames[i].size(),
                                reinterpret_cast<const jbyte*>(frames[i].data()));
        env->SetObjectArrayElement(result, i, byteArray);
        env->DeleteLocalRef(byteArray);
    }
    return result;
}

jbyteArray CreateByteArray(JNIEnv *env, const Bytes& bytes) {
    jbyteArray byteArray = env->NewByteArray(bytes.size());
    env->SetByteArrayRegion(byteArray, 0, bytes.size(),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return byteArray;
}

std::string GetStringField(JNIEnv *env, jobject obj, jclass cls, const char* fieldName) {
    jfieldID fid = env->GetFieldID(cls, fieldName, "Ljava/lang/String;");
    if (!fid) return "";
    jstring jstr = (jstring)env->GetObjectField(obj, fid);
    if (!jstr) return "";
    const char *cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string str(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    env->DeleteLocalRef(jstr);
    return str;
}

NavigationState NavigationStateFromName(const std::string& name) {
    if (name == "acquiring") return NavigationState::Acquiring;
    if (name == "planning") return NavigationState::Planning;
    if (name == "navigating") return NavigationState::Navigating;
    if (name == "rerouting") return NavigationState::Rerouting;
    if (name == "arrived") return NavigationState::Arrived;
    return NavigationState::Idle;
}

NetworkState NetworkStateFromName(const std::string& name) {
    if (name == "offline") return NetworkState::Offline;
    if (name == "connecting") return NetworkState::Connecting;
    return NetworkState::Online;
}

DisplayPage DisplayPageFromName(const std::string& name) {
    if (name == "speedometer") return DisplayPage::Speed;
    if (name == "heading") return DisplayPage::Compass;
    if (name == "music") return DisplayPage::Music;
    return DisplayPage::Navigation;
}

Maneuver ManeuverFromName(const std::string& name) {
    if (name == "turn_left") return Maneuver::Left;
    if (name == "turn_right") return Maneuver::Right;
    if (name == "keep_left") return Maneuver::SlightLeft;
    if (name == "keep_right") return Maneuver::SlightRight;
    if (name == "u_turn") return Maneuver::UTurnLeft;
    if (name == "roundabout") return Maneuver::Roundabout;
    if (name == "arrive") return Maneuver::Arrive;
    return Maneuver::Continue;
}

TrafficLevel TrafficLevelFromName(const std::string& name) {
    if (name == "clear") return TrafficLevel::FreeFlow;
    if (name == "slow") return TrafficLevel::Slow;
    if (name == "congested") return TrafficLevel::Congested;
    return TrafficLevel::Unknown;
}

NavigationSnapshot ConvertNavigationSnapshot(JNIEnv *env, jobject jSnapshot) {
    jclass cls = env->GetObjectClass(jSnapshot);
    NavigationSnapshot s{};

    s.state = NavigationStateFromName(GetStringField(env, jSnapshot, cls, "stateName"));
    s.network = NetworkStateFromName(GetStringField(env, jSnapshot, cls, "networkName"));
    s.display_page = DisplayPageFromName(GetStringField(env, jSnapshot, cls, "displayPageName"));
    s.maneuver = ManeuverFromName(GetStringField(env, jSnapshot, cls, "maneuverName"));
    s.traffic = TrafficLevelFromName(GetStringField(env, jSnapshot, cls, "trafficName"));

    bool hasDestination = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "hasDestination", "Z"));
    bool hasFix = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "hasFix", "Z"));
    bool gnssStale = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "gnssStale", "Z"));
    bool offRoute = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "offRoute", "Z"));
    bool routeRequestInFlight = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "routeRequestInFlight", "Z"));
    bool trafficRequestInFlight = env->GetBooleanField(jSnapshot, env->GetFieldID(cls, "trafficRequestInFlight", "Z"));

    if (hasDestination) s.flags |= NavigationHasDestination;
    if (hasFix) s.flags |= NavigationHasFix;
    if (gnssStale) s.flags |= NavigationGnssStale;
    if (offRoute) s.flags |= NavigationOffRoute;
    if (routeRequestInFlight) s.flags |= NavigationRouteRequestInFlight;
    if (trafficRequestInFlight) s.flags |= NavigationTrafficRequestInFlight;

    s.route_token = env->GetLongField(jSnapshot, env->GetFieldID(cls, "routeToken", "J"));
    s.route_generation = env->GetLongField(jSnapshot, env->GetFieldID(cls, "routeGeneration", "J"));
    s.maneuver_id = env->GetLongField(jSnapshot, env->GetFieldID(cls, "maneuverID", "J"));
    s.distance_to_maneuver_m = env->GetLongField(jSnapshot, env->GetFieldID(cls, "distanceToManeuverM", "J"));
    s.remaining_distance_m = env->GetLongField(jSnapshot, env->GetFieldID(cls, "remainingDistanceM", "J"));
    s.remaining_duration_s = env->GetLongField(jSnapshot, env->GetFieldID(cls, "remainingDurationS", "J"));
    s.route_progress_m = env->GetLongField(jSnapshot, env->GetFieldID(cls, "routeProgressM", "J"));
    s.total_distance_m = env->GetLongField(jSnapshot, env->GetFieldID(cls, "totalDistanceM", "J"));

    s.speed_deci_kph = env->GetIntField(jSnapshot, env->GetFieldID(cls, "speedDeciKPH", "I"));
    s.speed_limit_kph = env->GetIntField(jSnapshot, env->GetFieldID(cls, "speedLimitKPH", "I"));
    s.heading_cdeg = env->GetIntField(jSnapshot, env->GetFieldID(cls, "headingCentiDegrees", "I"));
    s.accuracy_dm = env->GetIntField(jSnapshot, env->GetFieldID(cls, "accuracyDecimeters", "I"));
    s.cross_track_dm = env->GetIntField(jSnapshot, env->GetFieldID(cls, "crossTrackDecimeters", "I"));
    s.roundabout_exit = env->GetIntField(jSnapshot, env->GetFieldID(cls, "roundaboutExit", "I"));

    std::string inst = GetStringField(env, jSnapshot, cls, "instructionText");
    s.instruction = inst;

    env->DeleteLocalRef(cls);
    return s;
}

std::vector<Bytes> EncodeMessageToFrames(CodecStorage* storage, MessageType type, const Message& msg, bool ack_requested, bool urgent) {
    auto payload_result = encode_message(msg);
    if (payload_result.error != Error::None) return {};

    const Bytes& payload = payload_result.value;

    std::uint8_t flags = 0;
    if (ack_requested) flags |= AckRequested;
    if (urgent) flags |= Urgent;

    storage->last_encoded_sequence = storage->sequence.next();
    auto frames_result = fragment_message(type, storage->last_encoded_sequence,
                                          ByteView(payload.data(), payload.size()),
                                          storage->max_frame_size, flags, kProtocolVersion);
    if (frames_result.error == Error::None) {
        return frames_result.value;
    }
    return {};
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeInit(JNIEnv *env, jobject thiz, jint maximum_frame_size) {
    auto* storage = new CodecStorage(maximum_frame_size);
    return reinterpret_cast<jlong>(storage);
}

extern "C" JNIEXPORT void JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeDestroy(JNIEnv *env, jobject thiz, jlong ptr) {
    delete reinterpret_cast<CodecStorage*>(ptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeSetMaximumFrameSize(JNIEnv *env, jobject thiz, jlong ptr, jint maximum_frame_size) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);
    storage->max_frame_size = std::clamp<std::size_t>(
        maximum_frame_size, kFrameOverhead + 1, kMaxBleAttributeValueSize);
}

extern "C" JNIEXPORT void JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeResetInboundState(JNIEnv *env, jobject thiz, jlong ptr) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);
    storage->reassembler.reset();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodePhoneStarting(JNIEnv *env, jobject thiz, jlong ptr, jlong sessionID) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);

    ConnectionStatus status{};
    status.role = EndpointRole::Phone;
    status.state = ConnectionState::Starting;
    status.minimum_version = kProtocolVersion;
    status.maximum_version = kProtocolVersion;
    status.capabilities = kPhoneToDeviceGattRequirements;
    status.session_id = static_cast<uint32_t>(sessionID);
    status.max_frame_size = static_cast<uint16_t>(storage->max_frame_size);
    status.heartbeat_interval_ms = 1000;

    auto frames = EncodeMessageToFrames(storage, MessageType::ConnectionStatus, status, false, true);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodePhoneReady(JNIEnv *env, jobject thiz, jlong ptr, jlong sessionID) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);

    ConnectionStatus status{};
    status.role = EndpointRole::Phone;
    status.state = ConnectionState::Ready;
    status.minimum_version = kProtocolVersion;
    status.maximum_version = kProtocolVersion;
    status.capabilities = kPhoneToDeviceGattRequirements;
    status.session_id = static_cast<uint32_t>(sessionID);
    status.max_frame_size = static_cast<uint16_t>(storage->max_frame_size);
    status.heartbeat_interval_ms = 1000;

    auto frames = EncodeMessageToFrames(storage, MessageType::ConnectionStatus, status, false, true);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodeHeartbeat(JNIEnv *env, jobject thiz, jlong ptr, jlong sessionID, jlong monotonicMs) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);

    Heartbeat hb{};
    hb.session_id = static_cast<uint32_t>(sessionID);

    auto frames = EncodeMessageToFrames(storage, MessageType::Heartbeat, hb, false, false);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodeAck(JNIEnv *env, jobject thiz, jlong ptr, jint sequence, jint status, jint commandID) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);

    Ack ack{};
    ack.acknowledged_sequence = static_cast<uint16_t>(sequence);
    ack.status = static_cast<AckStatus>(status);
    ack.command_id = static_cast<uint16_t>(commandID);

    auto frames = EncodeMessageToFrames(storage, MessageType::Ack, ack, true, true);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodeNavigationSnapshot(JNIEnv *env, jobject thiz, jlong ptr, jobject snapshot) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);
    NavigationSnapshot s = ConvertNavigationSnapshot(env, snapshot);

    auto frames = EncodeMessageToFrames(storage, MessageType::NavigationSnapshot, s, false, false);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodeNavigationPayloadForGoldenCheck(JNIEnv *env, jobject thiz, jlong ptr, jobject snapshot) {
    NavigationSnapshot s = ConvertNavigationSnapshot(env, snapshot);
    auto result = encode_message(s);
    if (result.error != Error::None) return nullptr;
    return CreateByteArray(env, result.value);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeEncodeMediaState(JNIEnv *env, jobject thiz, jlong ptr, jobject state) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);
    jclass cls = env->GetObjectClass(state);

    MediaState m{};
    /* Not implementing fields due to constraints in task 6 */
    env->DeleteLocalRef(cls);

    auto frames = EncodeMessageToFrames(storage, MessageType::MediaState, m, false, false);
    return CreateByteArrayArray(env, frames);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativePushDeviceFrame(JNIEnv *env, jobject thiz, jlong ptr, jbyteArray frame, jlong receivedAtMs) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);

    jsize len = env->GetArrayLength(frame);
    std::vector<uint8_t> frameBytes(len);
    env->GetByteArrayRegion(frame, 0, len, reinterpret_cast<jbyte*>(frameBytes.data()));

    auto result = storage->reassembler.push(ByteView(frameBytes.data(), len), receivedAtMs);

    jclass msgCls = env->FindClass("com/motogps/android/jni/MotoBLEInboundMessage");
    jmethodID msgCtor = env->GetMethodID(msgCls, "<init>", "(IZZZLcom/motogps/android/jni/MotoBLEConnectionStatus;Lcom/motogps/android/jni/MotoBLEHeartbeat;Lcom/motogps/android/jni/MotoBLEAcknowledgement;Lcom/motogps/android/jni/MotoBLEDeviceCommand;)V");

    jobject jConnStatus = nullptr;
    jobject jHeartbeat = nullptr;
    jobject jAck = nullptr;
    jobject jCommand = nullptr;

    bool complete = false;
    bool duplicate = false;

    if (result.state == ReassemblyState::Complete) {
        complete = true;
        const auto& msg = result.message;

        auto decoded = decode_message(msg.type, ByteView(msg.payload.data(), msg.payload.size()));

        if (decoded.error == Error::None) {
            const auto& payload = decoded.value;

            if (std::holds_alternative<ConnectionStatus>(payload)) {
                const auto& s = std::get<ConnectionStatus>(payload);
                jclass csCls = env->FindClass("com/motogps/android/jni/MotoBLEConnectionStatus");
                jmethodID csCtor = env->GetMethodID(csCls, "<init>", "(IIIIJJI)V");
                jConnStatus = env->NewObject(csCls, csCtor,
                                           (jint)s.role, (jint)s.state,
                                           (jint)s.minimum_version, (jint)s.maximum_version,
                                           (jlong)s.capabilities, (jlong)s.session_id,
                                           (jint)s.max_frame_size, (jint)s.heartbeat_interval_ms);
                env->DeleteLocalRef(csCls);
            }
            else if (std::holds_alternative<DeviceCommand>(payload)) {
                const auto& c = std::get<DeviceCommand>(payload);
                jclass dcCls = env->FindClass("com/motogps/android/jni/MotoBLEDeviceCommand");
                jmethodID dcCtor = env->GetMethodID(dcCls, "<init>", "(IZIIIIJ)V");
                jCommand = env->NewObject(dcCls, dcCtor,
                                        (jint)msg.sequence, (jboolean)false /*ack req*/,
                                        (jint)c.kind, (jint)c.command_id, (jint)c.page,
                                        (jint)c.x, (jint)c.y, (jlong)c.event_time_ms);
                env->DeleteLocalRef(dcCls);
            }
        }
    } else if (result.state == ReassemblyState::DuplicateMessage || result.state == ReassemblyState::DuplicateFragment) {
        duplicate = true;
    }

    jobject jMsg = env->NewObject(msgCls, msgCtor,
                                (jint)result.message.sequence, (jboolean)false,
                                (jboolean)complete, (jboolean)duplicate,
                                jConnStatus, jHeartbeat, jAck, jCommand);

    if (jConnStatus) env->DeleteLocalRef(jConnStatus);
    if (jHeartbeat) env->DeleteLocalRef(jHeartbeat);
    if (jAck) env->DeleteLocalRef(jAck);
    if (jCommand) env->DeleteLocalRef(jCommand);
    env->DeleteLocalRef(msgCls);

    return jMsg;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_motogps_android_jni_MotoBLEProtocolCodec_nativeGetLastEncodedSequence(JNIEnv *env, jobject thiz, jlong ptr) {
    auto* storage = reinterpret_cast<CodecStorage*>(ptr);
    return storage->last_encoded_sequence;
}
