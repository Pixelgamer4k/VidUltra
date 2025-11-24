import React, { useEffect, useState } from 'react';
import { StyleSheet, View, Text } from 'react-native';
import { Camera } from 'react-native-vision-camera';
import { CameraView } from './src/components/camera/CameraView';
import { RecordButton } from './src/components/ui/RecordButton';

export default function App() {
  const [hasPermission, setHasPermission] = useState(false);

  useEffect(() => {
    (async () => {
      const cameraPermission = await Camera.requestCameraPermission();
      const micPermission = await Camera.requestMicrophonePermission();
      setHasPermission(cameraPermission === 'granted' && micPermission === 'granted');
    })();
  }, []);

  if (!hasPermission) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>Requesting permissions...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <CameraView />

      {/* Record Button - Bottom Right */}
      <View style={styles.recordButtonContainer}>
        <RecordButton />
      </View>

      {/* Resolution Badge - Bottom Center */}
      <View style={styles.resolutionBadge}>
        <Text style={styles.resolutionText}>4K 60</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000',
  },
  text: {
    color: '#fff',
    fontSize: 16,
  },
  recordButtonContainer: {
    position: 'absolute',
    bottom: 20,
    right: 20,
  },
  resolutionBadge: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    backgroundColor: 'rgba(15, 15, 15, 0.95)',
    paddingHorizontal: 16,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: 'rgba(255, 255, 255, 0.1)',
  },
  resolutionText: {
    color: '#FFD700',
    fontSize: 12,
    fontWeight: '600',
  },
});
