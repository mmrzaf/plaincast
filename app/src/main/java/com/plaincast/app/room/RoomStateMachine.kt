package com.plaincast.app.room

import com.plaincast.app.model.MediaLifecycle
import com.plaincast.app.model.RoomLifecycle

object RoomStateMachine {
    private val roomTransitions = mapOf(
        RoomLifecycle.Idle to setOf(RoomLifecycle.Creating, RoomLifecycle.Joining),
        RoomLifecycle.Creating to setOf(RoomLifecycle.Connected, RoomLifecycle.Failed, RoomLifecycle.Leaving),
        RoomLifecycle.Joining to setOf(RoomLifecycle.Connected, RoomLifecycle.Reconnecting, RoomLifecycle.Failed, RoomLifecycle.Leaving),
        RoomLifecycle.Connected to setOf(RoomLifecycle.Reconnecting, RoomLifecycle.Leaving, RoomLifecycle.Failed),
        RoomLifecycle.Reconnecting to setOf(RoomLifecycle.Connected, RoomLifecycle.Leaving, RoomLifecycle.Failed),
        RoomLifecycle.Leaving to setOf(RoomLifecycle.Idle),
        RoomLifecycle.Failed to setOf(RoomLifecycle.Creating, RoomLifecycle.Joining, RoomLifecycle.Leaving, RoomLifecycle.Idle),
    )

    private val mediaTransitions = mapOf(
        MediaLifecycle.Stopped to setOf(MediaLifecycle.Starting, MediaLifecycle.Failed),
        MediaLifecycle.Starting to setOf(MediaLifecycle.Live, MediaLifecycle.Stopped, MediaLifecycle.Failed),
        MediaLifecycle.Live to setOf(MediaLifecycle.Stopped, MediaLifecycle.Failed),
        MediaLifecycle.Failed to setOf(MediaLifecycle.Starting, MediaLifecycle.Stopped),
    )

    fun requireRoomTransition(from: RoomLifecycle, to: RoomLifecycle) {
        if (from == to) return
        require(to in roomTransitions.getValue(from)) { "Invalid room transition: $from → $to" }
    }

    fun requireMediaTransition(from: MediaLifecycle, to: MediaLifecycle) {
        if (from == to) return
        require(to in mediaTransitions.getValue(from)) { "Invalid media transition: $from → $to" }
    }
}
