package com.plaincast.app.room

import com.plaincast.app.model.MediaLifecycle
import com.plaincast.app.model.RoomLifecycle
import org.junit.Test

class RoomStateMachineTest {
    @Test
    fun reconnectPathIsExplicitlyAllowed() {
        RoomStateMachine.requireRoomTransition(RoomLifecycle.Connected, RoomLifecycle.Reconnecting)
        RoomStateMachine.requireRoomTransition(RoomLifecycle.Reconnecting, RoomLifecycle.Connected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun impossibleDirectRoomTransitionIsRejected() {
        RoomStateMachine.requireRoomTransition(RoomLifecycle.Idle, RoomLifecycle.Connected)
    }

    @Test
    fun explicitMediaStartupAndStopPathIsAllowed() {
        RoomStateMachine.requireMediaTransition(MediaLifecycle.Stopped, MediaLifecycle.Starting)
        RoomStateMachine.requireMediaTransition(MediaLifecycle.Starting, MediaLifecycle.Live)
        RoomStateMachine.requireMediaTransition(MediaLifecycle.Live, MediaLifecycle.Stopped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun stoppedMediaCannotJumpDirectlyToLive() {
        RoomStateMachine.requireMediaTransition(MediaLifecycle.Stopped, MediaLifecycle.Live)
    }

    @Test(expected = IllegalArgumentException::class)
    fun failedMediaCannotJumpDirectlyToLive() {
        RoomStateMachine.requireMediaTransition(MediaLifecycle.Failed, MediaLifecycle.Live)
    }
}
