import { Routes } from '@angular/router';
import { authGuard, organizerGuard } from './core/guards';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./features/events/events-list').then((m) => m.EventsList),
  },
  {
    path: 'events',
    loadComponent: () => import('./features/events/events-list').then((m) => m.EventsList),
  },
  {
    path: 'events/:id',
    loadComponent: () => import('./features/events/event-detail').then((m) => m.EventDetail),
  },
  {
    path: 'login',
    loadComponent: () => import('./features/auth/login').then((m) => m.Login),
  },
  {
    path: 'register',
    loadComponent: () => import('./features/auth/register').then((m) => m.Register),
  },
  {
    path: 'my-bookings',
    canActivate: [authGuard],
    loadComponent: () => import('./features/bookings/my-bookings').then((m) => m.MyBookings),
  },
  {
    path: 'organizer',
    canActivate: [organizerGuard],
    loadComponent: () => import('./features/organizer/organizer').then((m) => m.Organizer),
  },
  { path: '**', redirectTo: '' },
];
