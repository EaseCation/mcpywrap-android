# coding: utf-8
import mod.client.extraClientApi as api


class WelcomeClient(api.GetClientSystemCls()):
    def __init__(self, namespace, system_name):
        super(WelcomeClient, self).__init__(namespace, system_name)
        self.ListenForEvent(api.GetEngineNamespace(), api.GetEngineSystemName(),
                            "OnLocalPlayerStopLoading", self, self.on_loaded)

    def on_loaded(self, args):
        # 修改下面引号里的文字，保留两边的引号。
        game = api.GetEngineCompFactory().CreateGame(api.GetLevelId())
        game.SetTipMessage("欢迎来到我的世界！")

    def Destroy(self):
        self.UnListenForEvent(api.GetEngineNamespace(), api.GetEngineSystemName(),
                              "OnLocalPlayerStopLoading", self, self.on_loaded)
