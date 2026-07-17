// sockjs-client expects a Node-style `global`; provide it in the browser.
(window as unknown as { global: unknown }).global = window;

import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig)
  .catch((err) => console.error(err));
