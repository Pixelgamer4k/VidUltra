import React from 'react';
import { View, StyleSheet } from 'react-native';

export function GridOverlay() {
    return (
        <View style={styles.gridContainer} pointerEvents="none">
            <View style={styles.gridLineVertical} />
            <View style={styles.gridLineVertical} />
            <View style={styles.gridLineHorizontal} />
            <View style={styles.gridLineHorizontal} />
            {/* Center Crosshair */}
            <View style={styles.crosshair} />
        </View>
    );
}

const styles = StyleSheet.create({
    gridContainer: {
        ...StyleSheet.absoluteFillObject,
        justifyContent: 'center',
        alignItems: 'center',
    },
    gridLineVertical: {
        position: 'absolute',
        width: 1,
        height: '100%',
        backgroundColor: 'rgba(255, 255, 255, 0.3)',
        left: '33.33%',
    },
    gridLineHorizontal: {
        position: 'absolute',
        height: 1,
        width: '100%',
        backgroundColor: 'rgba(255, 255, 255, 0.3)',
        top: '33.33%',
    },
    crosshair: {
        width: 20,
        height: 20,
        borderWidth: 1,
        borderColor: 'rgba(255, 255, 0, 0.5)',
        position: 'absolute',
    }
});
