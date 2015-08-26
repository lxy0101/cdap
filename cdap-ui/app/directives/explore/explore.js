angular.module(PKG.name + '.commons')
  .directive('myExplore', function () {

    return {
      restrict: 'E',
      scope: {
        type: '=',
        name: '='
      },
      templateUrl: 'explore/explore.html',
      controller: myExploreCtrl,
      controllerAs: 'MyExploreCtrl'
    };


    function myExploreCtrl ($scope, MyDataSource, myExploreApi, $http, $state, $bootstrapModal, myCdapUrl, $timeout) {
        var vm = this;

        var dataSrc = new MyDataSource($scope);
        var isFirstTime = true;
        vm.queries = [];
        vm.currentPage = 1;
        var params = {
          namespace: $state.params.namespace,
          scope: $scope
        };

        $scope.$watch('name', function() {
          vm.query = 'SELECT * FROM ' + $scope.type + '_' + $scope.name + ' LIMIT 5';
        });

        vm.execute = function() {
          myExploreApi.postQuery(params, { query: vm.query }, vm.getQueries);
          vm.currentPage = 1;
        };

        vm.getQueries = function() {

          myExploreApi.getQueries(params)
            .$promise
            .then(function (queries) {
              if (!isFirstTime) {
                vm.previous = vm.queries.map(function (q) { return q.query_handle; });
              }

              isFirstTime = false;

              vm.queries = queries;

              // Polling for status
              angular.forEach(vm.queries, function(q) {
                q.isOpen = false;
                if (q.status !== 'FINISHED') {

                  // TODO: change to use myExploreApi once figure out how to manually stop poll with $resource
                  var promise = dataSrc.poll({
                    _cdapPath: '/data/explore/queries/' +
                                q.query_handle + '/status',
                    interval: 1000
                  }, function(res) {
                    q.status = res.status;

                    if (res.status === 'FINISHED') {
                      dataSrc.stopPoll(q.pollid);
                    }
                  });
                  q.pollid = promise.__pollId__;
                }
              });

              $timeout(function () {
                vm.previous = vm.queries.map(function (q) { return q.query_handle; });
              }, 1000);

            });
        };

        vm.getQueries();

        vm.preview = function (query) {
          $bootstrapModal.open({
            templateUrl: 'explore/preview-modal.html',
            size: 'lg',
            resolve: {
              query: function () { return query; }
            },
            controller: ['$scope', 'myExploreApi', function ($scope, myExploreApi) {
              var params = {
                queryhandle: query.query_handle,
                scope: $scope
              };

              myExploreApi.getQuerySchema(params)
                .$promise
                .then(function (res) {
                  angular.forEach(res, function(v) {
                    v.name = v.name.split('.')[1];
                  });

                  $scope.schema = res;
                });

              myExploreApi.getQueryPreview(params, {},
                function (res) {
                  $scope.rows = res;
                });

            }]
          });
        };


        vm.download = function(query) {
          query.downloading = true;
          query.is_active = false; // this will prevent user from previewing after download

          // Cannot use $resource: http://stackoverflow.com/questions/24876593/resource-query-return-split-strings-array-of-char-instead-of-a-string

          // The files are being store in the node proxy

          $http.post('/downloadQuery', {
            'backendUrl': myCdapUrl.constructUrl({_cdapPath: '/data/explore/queries/' + query.query_handle + '/download'}),
            'queryHandle': query.query_handle
          })
            .success(function(res) {

              var url = 'http://' + window.location.host + res;

              var element = angular.element('<a/>');
              element.attr({
                href: url,
                target: '_self'
              })[0].click();

              query.downloading = false;
            })
            .error(function() {
              console.info('Error downloading query');
              query.downloading = false;
            });
        };

        vm.clone = function (query) {
          vm.query = query.statement;
        };

      }


  });
