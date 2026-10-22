# coding: utf-8
from mod.common.mod import Mod
import mod.client.extraClientApi as clientApi


@Mod.Binding(name="WelcomeDemo", version="1.0.0")
class WelcomeMod(object):
    @Mod.InitClient()
    def init_client(self):
        clientApi.RegisterSystem(
            "WelcomeDemo", "WelcomeClient", "WelcomeDemo.client.WelcomeClient"
        )
