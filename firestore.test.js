const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const CHARLIE_UID = "charlie_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("feedPosts").where("visibility", "==", "PUBLIC").get());
  await assertFails(unauthDb.collection("pulseRooms").where("active", "==", true).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      handle: "alice",
      discoverable: true,
      topGenres: ["Electronic"],
      listeningMinutes: 10,
      streakDays: 1,
      createdAt: new Date(Date.now() - 1000),
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("UserProfile and private syncedLibrary: owner isolation, shadow field rejection, and list query alignment", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      handle: "alice_wave",
      discoverable: true,
      topGenres: ["Synthwave"],
      sonicEnergy: 82,
      sonicValence: 65,
      sonicTempo: 75,
      sonicAcoustic: 20,
      sonicElectronic: 88,
      sonicChill: 40,
      listeningMinutes: 120,
      streakDays: 5,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Discoverable query succeeds, unfiltered query fails
  await assertSucceeds(aliceDb.collection("users").where("discoverable", "==", true).get());
  await assertFails(bobDb.collection("users").get());

  // Shadow update & cross-user mutation rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hacked",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Private syncedLibrary accessible only to owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("syncedLibrary").doc("trk_1").set({
      userId: ALICE_UID,
      trackId: "trk_1",
      title: "Midnight Pulse",
      artist: "Neon Echo",
      sourceType: "STREAM",
      liked: true,
      playCount: 3,
      lastPositionMs: 12000,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("syncedLibrary").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("syncedLibrary").get());
});

test("FeedPosts, Comments, and LyricNotes: public feed, likes, and author protections", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("feedPosts").doc("post_1").set({
      authorUid: ALICE_UID,
      authorName: "Alice",
      authorHandle: "alice_wave",
      visibility: "PUBLIC",
      postType: "TRACK",
      trackId: "trk_1",
      trackTitle: "Midnight Pulse",
      artist: "Neon Echo",
      caption: "Cannot stop replaying this drop",
      likeUids: [],
      commentCount: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(bobDb.collection("feedPosts").where("visibility", "==", "PUBLIC").get());
  await assertSucceeds(
    bobDb.collection("feedPosts").doc("post_1").update({
      likeUids: [BOB_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection("feedPosts").doc("post_1").update({
      caption: "Edited by Bob",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(
    bobDb.collection("feedPosts").doc("post_1").collection("comments").doc("c_1").set({
      postId: "post_1",
      postVisibility: "PUBLIC",
      authorUid: BOB_UID,
      authorName: "Bob",
      text: "Same here!",
      createdAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("feedPosts").doc("post_1").collection("comments").where("postVisibility", "==", "PUBLIC").get()
  );

  await assertSucceeds(
    aliceDb.collection("lyricNotes").doc("note_1").set({
      trackKey: "midnight_pulse_neon_echo",
      authorUid: ALICE_UID,
      authorName: "Alice",
      timestampMs: 42000,
      noteText: "Best synth transition at 0:42",
      shared: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    bobDb.collection("lyricNotes").where("trackKey", "==", "midnight_pulse_neon_echo").where("shared", "==", true).get()
  );
  await assertFails(
    bobDb.collection("lyricNotes").doc("note_1").update({
      noteText: "Tampered",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});

test("Direct Messages, Collaborative Playlists, FriendRequests, and PulseRooms", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const charlieDb = testEnv.authenticatedContext(CHARLIE_UID).firestore();
  const ts = new Date(Date.now() - 5000);

  // Friend request flow
  await assertSucceeds(
    aliceDb.collection("friendRequests").doc("req_ab").set({
      fromUid: ALICE_UID,
      fromName: "Alice",
      fromHandle: "alice_wave",
      toUid: BOB_UID,
      toName: "Bob",
      toHandle: "bob_beats",
      participantUids: [ALICE_UID, BOB_UID],
      status: "PENDING",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    bobDb.collection("friendRequests").doc("req_ab").update({
      status: "ACCEPTED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(charlieDb.collection("friendRequests").doc("req_ab").get());

  // 1:1 Conversation & DM
  await assertSucceeds(
    aliceDb.collection("conversations").doc("conv_ab").set({
      participantUids: [ALICE_UID, BOB_UID],
      participantLabel: "Alice & Bob",
      lastMessageText: "Check this track",
      lastSenderUid: ALICE_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    aliceDb.collection("conversations").doc("conv_ab").collection("messages").doc("msg_1").set({
      senderUid: ALICE_UID,
      senderName: "Alice",
      participantUids: [ALICE_UID, BOB_UID],
      text: "Listen to this!",
      attachmentType: "TRACK",
      attachedId: "trk_1",
      attachedTitle: "Midnight Pulse",
      attachedSubtitle: "Neon Echo",
      createdAt: ts,
    })
  );
  await assertSucceeds(
    bobDb.collection("conversations").doc("conv_ab").collection("messages").where("participantUids", "==", [ALICE_UID, BOB_UID]).get()
  );
  await assertFails(charlieDb.collection("conversations").doc("conv_ab").get());

  // Collaborative Playlist
  await assertSucceeds(
    aliceDb.collection("playlists").doc("pl_1").set({
      ownerUid: ALICE_UID,
      ownerName: "Alice",
      collaboratorUids: [ALICE_UID, BOB_UID],
      title: "Night Drive Blend",
      description: "Shared synth mix",
      collaborative: true,
      discoverable: false,
      trackItems: [],
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    bobDb.collection("playlists").doc("pl_1").update({
      trackItems: [{ trackId: "trk_1", title: "Midnight Pulse", artist: "Neon Echo", addedByUid: BOB_UID, upvotes: 1 }],
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(charlieDb.collection("playlists").doc("pl_1").get());

  // PulseRoom
  await assertSucceeds(
    aliceDb.collection("pulseRooms").doc("room_1").set({
      hostUid: ALICE_UID,
      hostName: "Alice",
      roomName: "Late Night Lounge",
      moodTag: "Chillwave",
      active: true,
      memberUids: [ALICE_UID],
      currentTrackId: "trk_1",
      trackTitle: "Midnight Pulse",
      artist: "Neon Echo",
      playing: true,
      positionMs: 0,
      soundscapePreset: "Velvet Club",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(
    bobDb.collection("pulseRooms").doc("room_1").update({
      memberUids: [ALICE_UID, BOB_UID],
      updatedAt: new Date(Date.now() - 1000),
    })
  );
  await assertFails(
    bobDb.collection("pulseRooms").doc("room_1").update({
      playing: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
