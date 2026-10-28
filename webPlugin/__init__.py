"""Buddyverse PWA: static files only (dashboard/), served by Hermes at /dashboard-plugins/buddyverse/."""


def register(ctx):  # nothing to register: the gateway serves dashboard/ itself
    return None
