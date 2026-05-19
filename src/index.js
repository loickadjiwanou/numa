import { registerRootComponent } from 'expo';
import * as TaskManager from 'expo-task-manager';

const NUMA_TASK = 'NumaTask';

// Headless task : toute la logique tourne en Kotlin
TaskManager.defineTask(NUMA_TASK, async () => {
  return;
});

// Composant racine vide — l'app n'a pas d'UI
registerRootComponent(() => null);
