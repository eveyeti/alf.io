// Entrada de los tests (angular.json, test → main). Los specs los agrega el builder de karma.
//
// Va acá, y no en el main que el builder genera solo, porque la app tiene sus dependencias en
// projects/public/node_modules: los specs importan @angular/core/testing desde ahí, y el main del
// builder lo importaría desde frontend/node_modules, otra copia de Angular sin el TestBed inicializado.

import 'zone.js/testing';
import {getTestBed} from '@angular/core/testing';
import {BrowserDynamicTestingModule, platformBrowserDynamicTesting} from '@angular/platform-browser-dynamic/testing';

getTestBed().initTestEnvironment(
  BrowserDynamicTestingModule,
  platformBrowserDynamicTesting()
);
