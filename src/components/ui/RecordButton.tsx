import React from 'react';
import { View, Pressable, StyleSheet } from 'react-native';
import { useCameraStore } from '../../stores/cameraStore';

export function RecordButton() {
    const { isRecording, setRecording } = useCameraStore();

    return (
        <Pressable
            onPress={() => setRecording(!isRecording)}
            style={styles.container}
        >
            <View style={styles.outerRing} />
            <View style={[
                styles.innerCircle,
                isRecording && styles.innerSquare
            ]} />
        </Pressable>
    );
}

const styles = StyleSheet.create({
    container: {
        width: 84,
        height: 84,
        alignItems: 'center',
        justifyContent: 'center',
    },
    outerRing: {
        position: 'absolute',
        width: 84,
        height: 84,
        borderRadius: 42,
        borderWidth: 4,
        borderColor: 'rgba(255, 255, 255, 0.3)',
    },
    innerCircle: {
        width: 64,
        height: 64,
        borderRadius: 32,
        backgroundColor: '#FF3B30',
    },
    innerSquare: {
        borderRadius: 16,
    },
});
